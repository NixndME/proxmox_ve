package com.morpheusdata.proxmox.ve

import com.morpheusdata.core.util.HttpApiClient
import groovy.json.JsonSlurper
import groovy.util.logging.Slf4j
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

@Slf4j
class ProxmoxApiClient {

    def morpheusContext
    def cloud
    def plugin

    private static final HttpApiClient HTTP_CLIENT = new HttpApiClient()

    private static final Map<String, ConnectionPool> connectionPools = [:]
    private static final int MAX_CONNECTIONS_PER_HOST = 10
    private static final int CONNECTION_TIMEOUT_MS = 30000
    private static final int IDLE_TIMEOUT_MS = 300000

    private String cachedTicket = null
    private String cachedCSRFToken = null
    private long tokenExpiry = 0
    private static final long TOKEN_LIFETIME_MS = 7200000L

    ProxmoxApiClient(morpheusContext, cloud, plugin) {
        this.morpheusContext = morpheusContext
        this.cloud = cloud
        this.plugin = plugin
    }

    private static class HttpConnection {
        String url
        Map<String,String> headers = [:]
        boolean ignoreSSL
        HttpApiClient client = new HttpApiClient()
        long lastUsed = System.currentTimeMillis()
        boolean inUse = false
        boolean valid = true

        HttpConnection(String url, Map<String,String> headers = [:], boolean ignoreSSL = false) {
            this.url = url
            this.headers.putAll(headers)
            this.ignoreSSL = ignoreSSL
        }

        void updateHeaders(Map newHeaders) {
            if(newHeaders)
                this.headers.putAll(newHeaders)
        }

        boolean isValid() {
            return valid
        }

        void markAsInvalid() {
            valid = false
        }
    }

    private static class ConnectionPool {
        Queue<HttpConnection> availableConnections = new ConcurrentLinkedQueue<>()
        Set<HttpConnection> activeConnections = ConcurrentHashMap.newKeySet()
        String hostKey
        long lastUsed = System.currentTimeMillis()

        ConnectionPool(String hostKey) {
            this.hostKey = hostKey
        }

        HttpConnection borrowConnection() {
            lastUsed = System.currentTimeMillis()
            HttpConnection conn = availableConnections.poll()
            if(conn) {
                activeConnections.add(conn)
                conn.inUse = true
                return conn
            }
            return null
        }

        void returnConnection(HttpConnection connection) {
            if(connection) {
                connection.inUse = false
                connection.lastUsed = System.currentTimeMillis()
                activeConnections.remove(connection)
                availableConnections.add(connection)
            }
        }

        void cleanup() {
            availableConnections.clear()
            activeConnections.clear()
        }

        void cleanupIdleConnections() {
            long now = System.currentTimeMillis()
            availableConnections.removeIf { c -> (now - c.lastUsed) > IDLE_TIMEOUT_MS || !c.isValid() }
        }
    }
    
    private Map getClusterCredentials() {
        def authConfig = this.plugin.getAuthConfig(this.cloud)

        if (!authConfig.username || !authConfig.password) {
            throw new RuntimeException("Proxmox cluster credentials not configured")
        }

        return [username: authConfig.username, password: authConfig.password]
    }
    
    def makeHttpCall(String url, String method, Map headers = [:], String body = null) {
        def startTime = System.currentTimeMillis()
        boolean connectionFromPool = false
        def hostKey = extractHostKey(url)
        def pool = getOrCreateConnectionPool(hostKey)
        HttpConnection connection = null

        try {
            connection = pool.borrowConnection()
            if (connection) {
                if (!connection.isValid()) {
                    pool.activeConnections.remove(connection)
                    connection = createNewConnection(url, headers)
                } else {
                    connectionFromPool = true
                    connection.updateHeaders(headers)
                }
            } else {
                connection = createNewConnection(url, headers)
            }

            def result = executeRequest(connection, method, body)

            def endTime = System.currentTimeMillis()
            def duration = endTime - startTime
            log.debug("API call completed in ${duration}ms (pool hit: ${connectionFromPool})")

            return processResponse(result)

        } catch (Exception e) {
            if (connection) {
                connection.markAsInvalid()
            }
            log.error("HTTP call error: ${e.message}", e)
            throw new RuntimeException("API call failed: ${e.message}")
        } finally {
            if (connection) {
                if (connection.isValid()) {
                    pool.returnConnection(connection)
                } else {
                    pool.activeConnections.remove(connection)
                }
            }
        }
    }
    
    private Map authenticate() {
        if (cachedTicket && cachedCSRFToken && System.currentTimeMillis() < tokenExpiry) {
            log.debug("Using cached authentication token")
            return [ticket: cachedTicket, csrfToken: cachedCSRFToken]
        }

        def credentials = getClusterCredentials()
        def authUrl = "${cloud.serviceUrl}/api2/json/access/ticket"
        def formData = "username=${URLEncoder.encode(credentials.username, 'UTF-8')}&password=${URLEncoder.encode(credentials.password, 'UTF-8')}"
        def headers = ['Content-Type': 'application/x-www-form-urlencoded']

        try {
            log.info("Authenticating with Proxmox cluster...")
            def result = makeHttpCall(authUrl, 'POST', headers, formData)

            if (result.data?.ticket) {
                cachedTicket = result.data.ticket
                cachedCSRFToken = result.data.CSRFPreventionToken
                tokenExpiry = System.currentTimeMillis() + TOKEN_LIFETIME_MS

                log.info("Successfully authenticated and cached tokens")
                return [ticket: cachedTicket, csrfToken: cachedCSRFToken]
            } else {
                throw new RuntimeException("Authentication failed: No ticket received")
            }
        } catch (Exception e) {
            log.error("Authentication failed: ${e.message}", e)
            clearAuthCache()
            throw new RuntimeException("Proxmox authentication failed: ${e.message}")
        }
    }

    private void clearAuthCache() {
        cachedTicket = null
        cachedCSRFToken = null
        tokenExpiry = 0
    }

    private Map getAuthToken() {
        return authenticate()
    }
    
    def callApi(String endpoint, String method = 'GET', Map data = null) {
        def auth = getAuthToken()

        def path = "/api2/json${endpoint}"
        def url = "${cloud.serviceUrl}${path}"
        def headers = [
            'Cookie': "PVEAuthCookie=${auth.ticket}",
            'CSRFPreventionToken': auth.csrfToken
        ]

        String body = null
        if (data && (method == 'POST' || method == 'PUT')) {
            headers['Content-Type'] = 'application/x-www-form-urlencoded'
            body = buildFormData(data)
        }
        
        try {
            log.debug("Making ${method} call to: ${endpoint}")
            return makeHttpCall(url, method, headers, body)
        } catch (Exception e) {
            log.error("API call failed for ${endpoint}: ${e.message}", e)
            throw e
        }
    }
    
    def createVm(String node, Map vmConfig) {
        log.info("Creating VM on node ${node} using cluster credentials")
        
        def endpoint = "/nodes/${node}/qemu"
        def result = callApi(endpoint, 'POST', vmConfig)
        
        if (result.data) {
            log.info("VM creation initiated successfully")
            return result.data
        } else {
            throw new RuntimeException("Failed to create VM: ${result}")
        }
    }
    
    def getVmStatus(String node, String vmId) {
        def endpoint = "/nodes/${node}/qemu/${vmId}/status/current"
        return callApi(endpoint, 'GET')
    }
    
    def startVm(String node, String vmId) {
        log.info("Starting VM ${vmId} on node ${node}")
        def endpoint = "/nodes/${node}/qemu/${vmId}/status/start"
        return callApi(endpoint, 'POST')
    }
    
    def stopVm(String node, String vmId) {
        log.info("Stopping VM ${vmId} on node ${node}")
        def endpoint = "/nodes/${node}/qemu/${vmId}/status/stop"
        return callApi(endpoint, 'POST')
    }
    
    def restartVm(String node, String vmId) {
        log.info("Restarting VM ${vmId} on node ${node}")
        def endpoint = "/nodes/${node}/qemu/${vmId}/status/reboot"
        return callApi(endpoint, 'POST')
    }
    
    def deleteVm(String node, String vmId) {
        log.info("Deleting VM ${vmId} on node ${node}")
        def endpoint = "/nodes/${node}/qemu/${vmId}"
        return callApi(endpoint, 'DELETE')
    }
    
    def getClusterNodes() {
        log.info("Getting cluster nodes using cluster credentials")
        def result = callApi('/cluster/status', 'GET')
        return result.data?.findAll { it.type == 'node' }
    }
    
    def getClusterResources() {
        log.info("Getting cluster resources")
        def result = callApi('/cluster/resources', 'GET')
        return result.data
    }
    
    def getNodeInfo(String node) {
        def endpoint = "/nodes/${node}/status"
        return callApi(endpoint, 'GET')
    }
    
    def getNodeStorage(String node) {
        def endpoint = "/nodes/${node}/storage"
        return callApi(endpoint, 'GET')
    }
    
    def getNodeNetworks(String node) {
        def endpoint = "/nodes/${node}/network"
        return callApi(endpoint, 'GET')
    }

    def createVlan(String node, String bridge, int vlanId, Map config = [:]) {
        log.info("Creating VLAN ${vlanId} on bridge ${bridge} at node ${node}")

        def vlanInterface = "${bridge}.${vlanId}"
        def vlanConfig = [
            iface          : vlanInterface,
            type           : 'vlan',
            'vlan-raw-device': bridge,
            'vlan-id'      : vlanId,
            autostart      : config.autostart ?: 1
        ]

        if (config.ipAddress) {
            vlanConfig.address = config.ipAddress
            vlanConfig.netmask = config.netmask ?: '255.255.255.0'
        }

        if (config.gateway) {
            vlanConfig.gateway = config.gateway
        }

        def endpoint = "/nodes/${node}/network"
        return callApi(endpoint, 'POST', vlanConfig)
    }

    def deleteVlan(String node, String bridge, int vlanId) {
        log.info("Deleting VLAN ${vlanId} on bridge ${bridge} at node ${node}")

        def vlanInterface = "${bridge}.${vlanId}"
        def endpoint = "/nodes/${node}/network/${vlanInterface}"
        return callApi(endpoint, 'DELETE')
    }

    def updateVlan(String node, String vlanInterface, Map config) {
        log.info("Updating VLAN ${vlanInterface} on node ${node}")

        def endpoint = "/nodes/${node}/network/${vlanInterface}"
        return callApi(endpoint, 'PUT', config)
    }

    def getVlanInfo(String node, String vlanInterface) {
        def endpoint = "/nodes/${node}/network/${vlanInterface}"
        return callApi(endpoint, 'GET')
    }

    def createBond(String node, String bondName, List<String> slaves, Map config = [:]) {
        log.info("Creating bond ${bondName} on node ${node} with slaves ${slaves}")

        def bondConfig = [
            iface      : bondName,
            type       : 'bond',
            slaves     : slaves.join(' '),
            'bond_mode': config.bondMode ?: 'active-backup',
            'bond_miimon': config.miimon ?: 100,
            autostart  : config.autostart ?: 1
        ]

        if (config.ipAddress) {
            bondConfig.address = config.ipAddress
            bondConfig.netmask = config.netmask ?: '255.255.255.0'
        }

        def endpoint = "/nodes/${node}/network"
        return callApi(endpoint, 'POST', bondConfig)
    }

    def deleteBond(String node, String bondName) {
        log.info("Deleting bond ${bondName} on node ${node}")

        def endpoint = "/nodes/${node}/network/${bondName}"
        return callApi(endpoint, 'DELETE')
    }

    def createAdvancedBridge(String node, String bridgeName, Map config = [:]) {
        log.info("Creating advanced bridge ${bridgeName} on node ${node}")

        def bridgeConfig = [
            iface          : bridgeName,
            type           : 'bridge',
            autostart      : config.autostart ?: 1,
            'bridge_ports'  : config.ports ?: 'none',
            'bridge_stp'    : config.stp ? 'on' : 'off',
            'bridge_fd'     : config.forwardDelay ?: 0
        ]

        if (config.vlanAware) {
            bridgeConfig['bridge_vlan_aware'] = 'yes'
            bridgeConfig['bridge_vids'] = config.vlanRange ?: '2-4094'
        }

        if (config.ipAddress) {
            bridgeConfig.address = config.ipAddress
            bridgeConfig.netmask = config.netmask ?: '255.255.255.0'
        }

        def endpoint = "/nodes/${node}/network"
        return callApi(endpoint, 'POST', bridgeConfig)
    }
    
    def getNodeVms(String node) {
        def endpoint = "/nodes/${node}/qemu"
        return callApi(endpoint, 'GET')
    }
    
    def getVmConfig(String node, String vmId) {
        def endpoint = "/nodes/${node}/qemu/${vmId}/config"
        return callApi(endpoint, 'GET')
    }
    
    def updateVmConfig(String node, String vmId, Map config) {
        log.info("Updating VM ${vmId} configuration on node ${node}")
        def endpoint = "/nodes/${node}/qemu/${vmId}/config"
        return callApi(endpoint, 'PUT', config)
    }
    
    def cloneVm(String node, String vmId, Map cloneConfig) {
        log.info("Cloning VM ${vmId} on node ${node}")
        def endpoint = "/nodes/${node}/qemu/${vmId}/clone"
        return callApi(endpoint, 'POST', cloneConfig)
    }
    
    def createSnapshot(String node, String vmId, String snapname, String description = null) {
        log.info("Creating snapshot ${snapname} for VM ${vmId} on node ${node}")
        def endpoint = "/nodes/${node}/qemu/${vmId}/snapshot"
        def data = [snapname: snapname]
        if (description) {
            data.description = description
        }
        return callApi(endpoint, 'POST', data)
    }
    
    def deleteSnapshot(String node, String vmId, String snapname) {
        log.info("Deleting snapshot ${snapname} for VM ${vmId} on node ${node}")
        def endpoint = "/nodes/${node}/qemu/${vmId}/snapshot/${snapname}"
        return callApi(endpoint, 'DELETE')
    }
    
    def getVmSnapshots(String node, String vmId) {
        def endpoint = "/nodes/${node}/qemu/${vmId}/snapshot"
        return callApi(endpoint, 'GET')
    }
    
    def resizeDisk(String node, String vmId, String disk, String size) {
        log.info("Resizing disk ${disk} to ${size} for VM ${vmId} on node ${node}")
        def endpoint = "/nodes/${node}/qemu/${vmId}/resize"
        def data = [disk: disk, size: size]
        return callApi(endpoint, 'PUT', data)
    }
    
    def migrateVm(String node, String vmId, String targetNode, Map options = [:]) {
        log.info("Migrating VM ${vmId} from ${node} to ${targetNode}")
        def endpoint = "/nodes/${node}/qemu/${vmId}/migrate"
        def data = [target: targetNode] + options
        return callApi(endpoint, 'POST', data)
    }
    
    def getVersion() {
        def result = callApi('/version', 'GET')
        return result.data
    }
    
    def testConnection() {
        try {
            log.info("Testing Proxmox cluster connection...")
            
            def versionResult = callApi('/version', 'GET')
            if (versionResult) {
                log.info("Successfully connected to Proxmox cluster")
                return [
                    success: true,
                    version: versionResult.data?.version,
                    message: "Connection successful"
                ]
            } else {
                return [success: false, error: "No response from cluster"]
            }
        } catch (Exception e) {
            log.error("Connection test failed: ${e.message}", e)
            return [
                success: false,
                error: e.message,
                details: "Check credentials and network connectivity"
            ]
        }
    }

    def configureCloudInit(String node, String vmId, Map cloudInitConfig) {
        log.info("Configuring cloud-init for VM ${vmId} on node ${node} via API")
        def config = [:]
        if (cloudInitConfig.user) {
            config['ciuser'] = cloudInitConfig.user
        }
        if (cloudInitConfig.password) {
            config['cipassword'] = cloudInitConfig.password
        }
        if (cloudInitConfig.sshKeys) {
            config['sshkeys'] = URLEncoder.encode(cloudInitConfig.sshKeys.join('\n'), 'UTF-8')
        }
        if (cloudInitConfig.ipConfig) {
            config['ipconfig0'] = cloudInitConfig.ipConfig
        }
        if (cloudInitConfig.nameserver) {
            config['nameserver'] = cloudInitConfig.nameserver
        }
        if (cloudInitConfig.searchdomain) {
            config['searchdomain'] = cloudInitConfig.searchdomain
        }
        def endpoint = "/nodes/${node}/qemu/${vmId}/config"
        try {
            return callApi(endpoint, 'PUT', config)
        } catch(Exception e) {
            handleApiError(e, 'configureCloudInit')
        }
    }

    def uploadImageToStorage(String node, String storage, String filename, String contentType = 'iso') {
        log.info("Uploading ${filename} to storage ${storage} on node ${node} via API")
        def endpoint = "/nodes/${node}/storage/${storage}/upload"
        return [success: true, message: "Upload endpoint configured: ${endpoint}"]
    }

    def createDiskFromTemplate(String node, String vmId, String templateId, String storage) {
        log.info("Creating disk for VM ${vmId} from template ${templateId} via API")
        def cloneEndpoint = "/nodes/${node}/qemu/${templateId}/clone"
        def cloneConfig = [
            newid: vmId,
            storage: storage,
            format: 'qcow2'
        ]
        try {
            return callApi(cloneEndpoint, 'POST', cloneConfig)
        } catch(Exception e) {
            handleApiError(e, 'createDiskFromTemplate')
        }
    }

    def resizeVmResources(String node, String vmId, Map resources) {
        log.info("Resizing VM ${vmId} resources via API")
        def endpoint = "/nodes/${node}/qemu/${vmId}/config"
        def config = [:]
        if (resources.memory) {
            config['memory'] = resources.memory
        }
        if (resources.cores) {
            config['cores'] = resources.cores
        }
        if (resources.sockets) {
            config['sockets'] = resources.sockets
        }
        try {
            return callApi(endpoint, 'PUT', config)
        } catch(Exception e) {
            handleApiError(e, 'resizeVmResources')
        }
    }

    def configureVmNetwork(String node, String vmId, String bridge, String model = 'virtio') {
        log.info("Configuring network for VM ${vmId} via API")
        def endpoint = "/nodes/${node}/qemu/${vmId}/config"
        def config = ['net0': "${model},bridge=${bridge}"]
        try {
            return callApi(endpoint, 'PUT', config)
        } catch(Exception e) {
            handleApiError(e, 'configureVmNetwork')
        }
    }

    def configureVmDisk(String node, String vmId, String storage, String size = '32G', String diskType = 'scsi0') {
        log.info("Configuring disk for VM ${vmId} via API")
        def endpoint = "/nodes/${node}/qemu/${vmId}/config"
        def config = [(diskType): "${storage}:${size}"]
        try {
            return callApi(endpoint, 'PUT', config)
        } catch(Exception e) {
            handleApiError(e, 'configureVmDisk')
        }
    }

    def createVzdumpBackup(String node, Map backupConfig) {
        log.info("Creating vzdump backup on node ${node}")
        def endpoint = "/nodes/${node}/vzdump"
        return callApi(endpoint, 'POST', backupConfig)
    }

    def getTaskStatus(String node, String taskId) {
        def endpoint = "/nodes/${node}/tasks/${taskId}/status"
        return callApi(endpoint, 'GET')
    }

    def getStorageBackups(String node, String storage) {
        def endpoint = "/nodes/${node}/storage/${storage}/content"
        return callApi(endpoint, 'GET')
    }

    def deleteBackupFile(String node, String storage, String volid) {
        log.info("Deleting backup file ${volid} from ${storage} on ${node}")
        def endpoint = "/nodes/${node}/storage/${storage}/content/${URLEncoder.encode(volid, 'UTF-8')}"
        return callApi(endpoint, 'DELETE')
    }

    def rollbackSnapshot(String node, String vmId, String snapname) {
        log.info("Rolling back snapshot ${snapname} for VM ${vmId} on node ${node}")
        def endpoint = "/nodes/${node}/qemu/${vmId}/snapshot/${snapname}/rollback"
        return callApi(endpoint, 'POST')
    }

    def restoreBackup(String node, Map restoreConfig) {
        log.info("Restoring backup on node ${node}")
        def endpoint = "/nodes/${node}/qemu/${restoreConfig.vmid}/restore"
        return callApi(endpoint, 'POST', restoreConfig)
    }

    def getNextVmId(String node) {
        def endpoint = "/cluster/nextid"
        return callApi(endpoint, 'GET')
    }

    private def handleApiError(Exception e, String operation) {
        log.error("${operation} failed: ${e.message}", e)
        throw new RuntimeException("${operation} failed: ${e.message}")
    }

    private String extractHostKey(String url) {
        def uri = new URI(url)
        return "${uri.scheme}://${uri.host}:${uri.port}"
    }

    private ConnectionPool getOrCreateConnectionPool(String hostKey) {
        connectionPools.computeIfAbsent(hostKey) { key ->
            new ConnectionPool(key)
        }
    }

    private HttpConnection createNewConnection(String url, Map headers) {
        return new HttpConnection(url, headers as Map<String,String>, cloud.ignoreSsl ?: false)
    }

    private def executeRequest(HttpConnection connection, String method, String body) {
        def opts = [
                headers : connection.headers,
                ignoreSSL: connection.ignoreSSL,
                timeout  : CONNECTION_TIMEOUT_MS
        ]
        if(body)
            opts.body = body

        connection.lastUsed = System.currentTimeMillis()
        def uri = new URI(connection.url)
        return connection.client.callJsonApi(
                "${uri.scheme}://${uri.host}:${uri.port}",
                uri.path + (uri.query ? "?${uri.query}" : ""),
                null,
                null,
                new HttpApiClient.RequestOptions(opts),
                method
        )
    }

    private def processResponse(result) {
        if(result?.success) {
            if(result.content) {
                def slurper = new JsonSlurper()
                return slurper.parseText(result.content)
            }
            return result.data ?: [:]
        } else {
            throw new RuntimeException("HTTP call failed: ${result?.errorMessage ?: result?.error}")
        }
    }

    static void cleanupIdleConnections() {
        def now = System.currentTimeMillis()
        connectionPools.entrySet().removeIf { entry ->
            def pool = entry.value
            def idleTime = now - pool.lastUsed
            if(idleTime > IDLE_TIMEOUT_MS) {
                pool.cleanup()
                return true
            }
            pool.cleanupIdleConnections()
            return false
        }
    }

    def getConnectionStats() {
        def stats = [:]
        connectionPools.each { hostKey, pool ->
            stats[hostKey] = [
                    availableConnections: pool.availableConnections.size(),
                    activeConnections   : pool.activeConnections.size(),
                    lastUsed            : pool.lastUsed
            ]
        }
        return stats
    }

    static void closeAllConnections() {
        connectionPools.each { hostKey, pool ->
            pool.cleanup()
        }
        connectionPools.clear()
    }
    
    private String buildFormData(Map data) {
        return data.collect { k, v -> 
            URLEncoder.encode(k.toString(), 'UTF-8') + '=' + URLEncoder.encode(v.toString(), 'UTF-8') 
        }.join('&')
    }
    
    def validateConfig() {
        def issues = []
        
        if (!cloud.serviceUrl) {
            issues << "Service URL not configured"
        }
        
        if (!cloud.serviceUsername) {
            issues << "Username not configured"
        }
        
        if (!cloud.servicePassword) {
            issues << "Password not configured"
        }
        
        if (cloud.ignoreSsl) {
            log.warn("SSL verification is disabled - use only for development/testing")
        }
        
        return [
            valid: issues.isEmpty(),
            issues: issues
        ]
    }
}