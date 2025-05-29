package com.morpheusdata.proxmox.ve

import com.morpheusdata.core.util.HttpApiClient
import groovy.json.JsonSlurper
import groovy.util.logging.Slf4j
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock

@Slf4j
class ProxmoxApiClient {

    def morpheusContext
    def cloud
    def plugin

    private static final HttpApiClient HTTP_CLIENT = new HttpApiClient()

    private static final Map<String, ConnectionPool> connectionPools = new ConcurrentHashMap<>()
    private static final int MAX_CONNECTIONS_PER_HOST = 10
    private static final int CONNECTION_TIMEOUT_MS = 30000
    private static final int IDLE_TIMEOUT_MS = 300000
    private static final int MAX_RETRY_ATTEMPTS = 3
    private static final long RETRY_DELAY_MS = 1000

    private String cachedTicket = null
    private String cachedCSRFToken = null
    private long tokenExpiry = 0
    private static final long TOKEN_LIFETIME_MS = 7200000L
    private final ReentrantLock authLock = new ReentrantLock()

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
        AtomicInteger totalConnections = new AtomicInteger(0)
        String hostKey
        long lastUsed = System.currentTimeMillis()
        final ReentrantLock poolLock = new ReentrantLock()

        ConnectionPool(String hostKey) {
            this.hostKey = hostKey
        }

        HttpConnection borrowConnection() {
            poolLock.lock()
            try {
                lastUsed = System.currentTimeMillis()
                HttpConnection conn = availableConnections.poll()
                if(conn && conn.isValid()) {
                    activeConnections.add(conn)
                    conn.inUse = true
                    return conn
                }
                return null
            } finally {
                poolLock.unlock()
            }
        }

        boolean canCreateNewConnection() {
            return totalConnections.get() < MAX_CONNECTIONS_PER_HOST
        }

        void addNewConnection(HttpConnection conn) {
            poolLock.lock()
            try {
                if(totalConnections.get() < MAX_CONNECTIONS_PER_HOST) {
                    totalConnections.incrementAndGet()
                    activeConnections.add(conn)
                    conn.inUse = true
                }
            } finally {
                poolLock.unlock()
            }
        }

        void returnConnection(HttpConnection connection) {
            if(connection == null) return
            
            poolLock.lock()
            try {
                connection.inUse = false
                connection.lastUsed = System.currentTimeMillis()
                activeConnections.remove(connection)
                
                if(connection.isValid() && totalConnections.get() <= MAX_CONNECTIONS_PER_HOST) {
                    availableConnections.add(connection)
                } else {
                    totalConnections.decrementAndGet()
                }
            } finally {
                poolLock.unlock()
            }
        }

        void cleanup() {
            poolLock.lock()
            try {
                availableConnections.clear()
                activeConnections.clear()
                totalConnections.set(0)
            } finally {
                poolLock.unlock()
            }
        }

        void cleanupIdleConnections() {
            poolLock.lock()
            try {
                long now = System.currentTimeMillis()
                availableConnections.removeIf { c -> 
                    boolean remove = (now - c.lastUsed) > IDLE_TIMEOUT_MS || !c.isValid()
                    if(remove) totalConnections.decrementAndGet()
                    return remove
                }
            } finally {
                poolLock.unlock()
            }
        }
    }
    
    private Map getClusterCredentials() {
        def authConfig = this.plugin.getAuthConfig(this.cloud)

        if (!authConfig?.username || !authConfig?.password) {
            throw new RuntimeException("Proxmox cluster credentials not configured")
        }

        return [username: authConfig.username, password: authConfig.password]
    }

    private def makeHttpCallDirect(String url, String method, Map headers = [:], String body = null) {
        def opts = [
            headers: headers,
            ignoreSSL: cloud.ignoreSsl ?: false,
            timeout: CONNECTION_TIMEOUT_MS
        ]
        if(body) opts.body = body

        try {
            def result = HTTP_CLIENT.callJsonApi(url, '', null, null, 
                new HttpApiClient.RequestOptions(opts), method)
            return processResponse(result)
        } catch (Exception e) {
            log.error("Direct HTTP call failed: ${e.message}", e)
            throw e
        }
    }
    
    def makeHttpCall(String url, String method, Map headers = [:], String body = null) {
        def startTime = System.currentTimeMillis()
        boolean connectionFromPool = false
        def hostKey = extractHostKey(url)
        def pool = getOrCreateConnectionPool(hostKey)
        HttpConnection connection = null

        try {
            connection = pool.borrowConnection()
            if (!connection || !connection.isValid()) {
                if(pool.canCreateNewConnection()) {
                    connection = createNewConnection(url, headers)
                    pool.addNewConnection(connection)
                } else {
                    throw new RuntimeException("Connection pool exhausted for ${hostKey}")
                }
            } else {
                connectionFromPool = true
                connection.updateHeaders(headers)
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
            if(connection) {
                pool.returnConnection(connection)
            }
        }
    }
    
    private Map authenticate() {
        authLock.lock()
        try {
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
                def result = makeHttpCallDirect(authUrl, 'POST', headers, formData)

                if (result?.data?.ticket) {
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
        } finally {
            authLock.unlock()
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
        
        def lastException = null
        for(int attempt = 1; attempt <= MAX_RETRY_ATTEMPTS; attempt++) {
            try {
                log.debug("Making ${method} call to: ${endpoint} (attempt ${attempt})")
                return makeHttpCall(url, method, headers, body)
            } catch (Exception e) {
                lastException = e
                if(e.message?.contains("authentication") || e.message?.contains("401")) {
                    clearAuthCache()
                    auth = getAuthToken()
                    headers['Cookie'] = "PVEAuthCookie=${auth.ticket}"
                    headers['CSRFPreventionToken'] = auth.csrfToken
                }
                if(attempt < MAX_RETRY_ATTEMPTS) {
                    log.warn("API call failed, retrying... (attempt ${attempt}/${MAX_RETRY_ATTEMPTS})")
                    Thread.sleep(RETRY_DELAY_MS * attempt)
                }
            }
        }
        
        log.error("API call failed after ${MAX_RETRY_ATTEMPTS} attempts: ${lastException?.message}", lastException)
        throw lastException
    }
    
    def createVm(String node, Map vmConfig) {
        if(!node || !vmConfig) {
            throw new IllegalArgumentException("Node and VM config are required")
        }
        
        log.info("Creating VM on node ${node} using cluster credentials")
        
        def endpoint = "/nodes/${node}/qemu"
        def result = callApi(endpoint, 'POST', vmConfig)
        
        if (result?.data) {
            log.info("VM creation initiated successfully")
            return result.data
        } else {
            throw new RuntimeException("Failed to create VM: ${result}")
        }
    }
    
    def getVmStatus(String node, String vmId) {
        if(!node || !vmId) {
            throw new IllegalArgumentException("Node and VM ID are required")
        }
        def endpoint = "/nodes/${node}/qemu/${vmId}/status/current"
        return callApi(endpoint, 'GET')
    }
    
    def startVm(String node, String vmId) {
        if(!node || !vmId) {
            throw new IllegalArgumentException("Node and VM ID are required")
        }
        log.info("Starting VM ${vmId} on node ${node}")
        def endpoint = "/nodes/${node}/qemu/${vmId}/status/start"
        return callApi(endpoint, 'POST')
    }
    
    def stopVm(String node, String vmId) {
        if(!node || !vmId) {
            throw new IllegalArgumentException("Node and VM ID are required")
        }
        log.info("Stopping VM ${vmId} on node ${node}")
        def endpoint = "/nodes/${node}/qemu/${vmId}/status/stop"
        return callApi(endpoint, 'POST')
    }
    
    def restartVm(String node, String vmId) {
        if(!node || !vmId) {
            throw new IllegalArgumentException("Node and VM ID are required")
        }
        log.info("Restarting VM ${vmId} on node ${node}")
        def endpoint = "/nodes/${node}/qemu/${vmId}/status/reboot"
        return callApi(endpoint, 'POST')
    }
    
    def deleteVm(String node, String vmId) {
        if(!node || !vmId) {
            throw new IllegalArgumentException("Node and VM ID are required")
        }
        log.info("Deleting VM ${vmId} on node ${node}")
        def endpoint = "/nodes/${node}/qemu/${vmId}"
        return callApi(endpoint, 'DELETE')
    }
    
    def getClusterNodes() {
        log.info("Getting cluster nodes using cluster credentials")
        def result = callApi('/cluster/status', 'GET')
        return result?.data?.findAll { it.type == 'node' } ?: []
    }
    
    def getClusterResources() {
        log.info("Getting cluster resources")
        def result = callApi('/cluster/resources', 'GET')
        return result?.data ?: []
    }
    
    def getNodeInfo(String node) {
        if(!node) {
            throw new IllegalArgumentException("Node is required")
        }
        def endpoint = "/nodes/${node}/status"
        return callApi(endpoint, 'GET')
    }
    
    def getNodeStorage(String node) {
        if(!node) {
            throw new IllegalArgumentException("Node is required")
        }
        def endpoint = "/nodes/${node}/storage"
        return callApi(endpoint, 'GET')
    }
    
    def getNodeNetworks(String node) {
        if(!node) {
            throw new IllegalArgumentException("Node is required")
        }
        def endpoint = "/nodes/${node}/network"
        return callApi(endpoint, 'GET')
    }

    def createVlan(String node, String bridge, int vlanId, Map config = [:]) {
        if(!node || !bridge || vlanId <= 0) {
            throw new IllegalArgumentException("Node, bridge and valid VLAN ID are required")
        }
        
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
        if(!node || !bridge || vlanId <= 0) {
            throw new IllegalArgumentException("Node, bridge and valid VLAN ID are required")
        }
        
        log.info("Deleting VLAN ${vlanId} on bridge ${bridge} at node ${node}")

        def vlanInterface = "${bridge}.${vlanId}"
        def endpoint = "/nodes/${node}/network/${vlanInterface}"
        return callApi(endpoint, 'DELETE')
    }

    def updateVlan(String node, String vlanInterface, Map config) {
        if(!node || !vlanInterface || !config) {
            throw new IllegalArgumentException("Node, VLAN interface and config are required")
        }
        
        log.info("Updating VLAN ${vlanInterface} on node ${node}")

        def endpoint = "/nodes/${node}/network/${vlanInterface}"
        return callApi(endpoint, 'PUT', config)
    }

    def getVlanInfo(String node, String vlanInterface) {
        if(!node || !vlanInterface) {
            throw new IllegalArgumentException("Node and VLAN interface are required")
        }
        def endpoint = "/nodes/${node}/network/${vlanInterface}"
        return callApi(endpoint, 'GET')
    }

    def createBond(String node, String bondName, List<String> slaves, Map config = [:]) {
        if(!node || !bondName || !slaves || slaves.isEmpty()) {
            throw new IllegalArgumentException("Node, bond name and slaves are required")
        }
        
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
        if(!node || !bondName) {
            throw new IllegalArgumentException("Node and bond name are required")
        }
        
        log.info("Deleting bond ${bondName} on node ${node}")

        def endpoint = "/nodes/${node}/network/${bondName}"
        return callApi(endpoint, 'DELETE')
    }

    def createAdvancedBridge(String node, String bridgeName, Map config = [:]) {
        if(!node || !bridgeName) {
            throw new IllegalArgumentException("Node and bridge name are required")
        }
        
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
        if(!node) {
            throw new IllegalArgumentException("Node is required")
        }
        def endpoint = "/nodes/${node}/qemu"
        return callApi(endpoint, 'GET')
    }
    
    def getVmConfig(String node, String vmId) {
        if(!node || !vmId) {
            throw new IllegalArgumentException("Node and VM ID are required")
        }
        def endpoint = "/nodes/${node}/qemu/${vmId}/config"
        return callApi(endpoint, 'GET')
    }
    
    def updateVmConfig(String node, String vmId, Map config) {
        if(!node || !vmId || !config) {
            throw new IllegalArgumentException("Node, VM ID and config are required")
        }
        log.info("Updating VM ${vmId} configuration on node ${node}")
        def endpoint = "/nodes/${node}/qemu/${vmId}/config"
        return callApi(endpoint, 'PUT', config)
    }
    
    def cloneVm(String node, String vmId, Map cloneConfig) {
        if(!node || !vmId || !cloneConfig) {
            throw new IllegalArgumentException("Node, VM ID and clone config are required")
        }
        log.info("Cloning VM ${vmId} on node ${node}")
        def endpoint = "/nodes/${node}/qemu/${vmId}/clone"
        return callApi(endpoint, 'POST', cloneConfig)
    }
    
    def createSnapshot(String node, String vmId, String snapname, String description = null) {
        if(!node || !vmId || !snapname) {
            throw new IllegalArgumentException("Node, VM ID and snapshot name are required")
        }
        log.info("Creating snapshot ${snapname} for VM ${vmId} on node ${node}")
        def endpoint = "/nodes/${node}/qemu/${vmId}/snapshot"
        def data = [snapname: snapname]
        if (description) {
            data.description = description
        }
        return callApi(endpoint, 'POST', data)
    }
    
    def deleteSnapshot(String node, String vmId, String snapname) {
        if(!node || !vmId || !snapname) {
            throw new IllegalArgumentException("Node, VM ID and snapshot name are required")
        }
        log.info("Deleting snapshot ${snapname} for VM ${vmId} on node ${node}")
        def endpoint = "/nodes/${node}/qemu/${vmId}/snapshot/${snapname}"
        return callApi(endpoint, 'DELETE')
    }
    
    def getVmSnapshots(String node, String vmId) {
        if(!node || !vmId) {
            throw new IllegalArgumentException("Node and VM ID are required")
        }
        def endpoint = "/nodes/${node}/qemu/${vmId}/snapshot"
        return callApi(endpoint, 'GET')
    }
    
    def resizeDisk(String node, String vmId, String disk, String size) {
        if(!node || !vmId || !disk || !size) {
            throw new IllegalArgumentException("Node, VM ID, disk and size are required")
        }
        log.info("Resizing disk ${disk} to ${size} for VM ${vmId} on node ${node}")
        def endpoint = "/nodes/${node}/qemu/${vmId}/resize"
        def data = [disk: disk, size: size]
        return callApi(endpoint, 'PUT', data)
    }
    
    def migrateVm(String node, String vmId, String targetNode, Map options = [:]) {
        if(!node || !vmId || !targetNode) {
            throw new IllegalArgumentException("Node, VM ID and target node are required")
        }
        log.info("Migrating VM ${vmId} from ${node} to ${targetNode}")
        def endpoint = "/nodes/${node}/qemu/${vmId}/migrate"
        def data = [target: targetNode] + options
        return callApi(endpoint, 'POST', data)
    }
    
    def getVersion() {
        def result = callApi('/version', 'GET')
        return result?.data
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
        if(!node || !vmId || !cloudInitConfig) {
            throw new IllegalArgumentException("Node, VM ID and cloud-init config are required")
        }
        
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
        if(!node || !storage || !filename) {
            throw new IllegalArgumentException("Node, storage and filename are required")
        }
        log.info("Uploading ${filename} to storage ${storage} on node ${node} via API")
        def endpoint = "/nodes/${node}/storage/${storage}/upload"
        return [success: true, message: "Upload endpoint configured: ${endpoint}"]
    }

    def createDiskFromTemplate(String node, String vmId, String templateId, String storage) {
        if(!node || !vmId || !templateId || !storage) {
            throw new IllegalArgumentException("Node, VM ID, template ID and storage are required")
        }
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
        if(!node || !vmId || !resources) {
            throw new IllegalArgumentException("Node, VM ID and resources are required")
        }
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
        if(!node || !vmId || !bridge) {
            throw new IllegalArgumentException("Node, VM ID and bridge are required")
        }
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
        if(!node || !vmId || !storage) {
            throw new IllegalArgumentException("Node, VM ID and storage are required")
        }
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
        if(!node || !backupConfig) {
            throw new IllegalArgumentException("Node and backup config are required")
        }
        log.info("Creating vzdump backup on node ${node}")
        def endpoint = "/nodes/${node}/vzdump"
        return callApi(endpoint, 'POST', backupConfig)
    }

    def getTaskStatus(String node, String taskId) {
        if(!node || !taskId) {
            throw new IllegalArgumentException("Node and task ID are required")
        }
        def endpoint = "/nodes/${node}/tasks/${taskId}/status"
        return callApi(endpoint, 'GET')
    }

    def getStorageBackups(String node, String storage) {
        if(!node || !storage) {
            throw new IllegalArgumentException("Node and storage are required")
        }
        def endpoint = "/nodes/${node}/storage/${storage}/content"
        return callApi(endpoint, 'GET')
    }

    def deleteBackupFile(String node, String storage, String volid) {
        if(!node || !storage || !volid) {
            throw new IllegalArgumentException("Node, storage and volume ID are required")
        }
        log.info("Deleting backup file ${volid} from ${storage} on ${node}")
        def endpoint = "/nodes/${node}/storage/${storage}/content/${URLEncoder.encode(volid, 'UTF-8')}"
        return callApi(endpoint, 'DELETE')
    }

    def rollbackSnapshot(String node, String vmId, String snapname) {
        if(!node || !vmId || !snapname) {
            throw new IllegalArgumentException("Node, VM ID and snapshot name are required")
        }
        log.info("Rolling back snapshot ${snapname} for VM ${vmId} on node ${node}")
        def endpoint = "/nodes/${node}/qemu/${vmId}/snapshot/${snapname}/rollback"
        return callApi(endpoint, 'POST')
    }

    def restoreBackup(String node, Map restoreConfig) {
        if(!node || !restoreConfig || !restoreConfig.vmid) {
            throw new IllegalArgumentException("Node and restore config with VM ID are required")
        }
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
        try {
            def uri = new URI(url)
            return "${uri.scheme}://${uri.host}:${uri.port}"
        } catch(Exception e) {
            log.error("Failed to extract host key from URL: ${url}", e)
            return url
        }
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
            def errorMessage = result?.errorMessage ?: result?.error ?: "Unknown error"
            throw new RuntimeException("HTTP call failed: ${errorMessage}")
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
                    totalConnections    : pool.totalConnections.get(),
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
        if(!data) return ""
        
        return data.findAll { k, v -> v != null }
                   .collect { k, v -> 
                       URLEncoder.encode(k.toString(), 'UTF-8') + '=' + URLEncoder.encode(v.toString(), 'UTF-8') 
                   }.join('&')
    }
    
    def validateConfig() {
        def issues = []
        
        if (!cloud?.serviceUrl) {
            issues << "Service URL not configured"
        }
        
        try {
            def credentials = getClusterCredentials()
            if(!credentials.username) {
                issues << "Username not configured"
            }
            if(!credentials.password) {
                issues << "Password not configured"
            }
        } catch(Exception e) {
            issues << "Credentials not available: ${e.message}"
        }
        
        if (cloud?.ignoreSsl) {
            log.warn("SSL verification is disabled - use only for development/testing")
        }
        
        return [
            valid: issues.isEmpty(),
            issues: issues
        ]
    }
}