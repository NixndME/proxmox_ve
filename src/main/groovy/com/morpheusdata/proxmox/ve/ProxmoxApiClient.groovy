package com.morpheusdata.proxmox.ve

import com.morpheusdata.core.util.RestApiUtil
import groovy.json.JsonSlurper
import groovy.json.JsonBuilder
import groovy.util.logging.Slf4j
import java.net.URLEncoder

@Slf4j
class ProxmoxApiClient {

    def morpheusContext
    def cloud
    def plugin

    ProxmoxApiClient(morpheusContext, cloud, plugin) {
        this.morpheusContext = morpheusContext
        this.cloud = cloud
        this.plugin = plugin
    }
    
    /**
     * Get stored credentials from Morpheus Cloud configuration
     */
    private Map getClusterCredentials() {
        def authConfig = plugin.getAuthConfig(cloud)

        if (!authConfig.username || !authConfig.password) {
            throw new RuntimeException("Proxmox cluster credentials not configured")
        }

        return [username: authConfig.username, password: authConfig.password]
    }
    
    /**
     * Make HTTP call using Morpheus RestApiUtil
     */
    def makeHttpCall(String url, String method, Map headers = [:], String body = null) {
        try {
            def opts = [
                headers: headers,
                ignoreSSL: cloud.ignoreSsl ?: false,
                timeout: 30000
            ]
            
            if (body) {
                opts.body = body
            }
            
            log.debug("Making ${method} call to: ${url}")
            
            // Use correct RestApiUtil method
            def result = RestApiUtil.callApi(url, opts, method)
            
            if (result.success) {
                if (result.data) {
                    def jsonSlurper = new JsonSlurper()
                    return jsonSlurper.parseText(result.data)
                }
                return [:]
            } else {
                throw new RuntimeException("HTTP call failed: ${result.errorMessage ?: result.error}")
            }
        } catch (Exception e) {
            log.error("HTTP call error for ${url}: ${e.message}", e)
            throw new RuntimeException("API call failed: ${e.message}")
        }
    }
    
    /**
     * Authenticate with Proxmox cluster and get ticket
     */
    def authenticate() {
        def credentials = getClusterCredentials()
        
        def authUrl = "${cloud.serviceUrl}/api2/json/access/ticket"
        def formData = "username=${URLEncoder.encode(credentials.username, 'UTF-8')}&password=${URLEncoder.encode(credentials.password, 'UTF-8')}"
        
        def headers = [
            'Content-Type': 'application/x-www-form-urlencoded'
        ]
        
        try {
            log.info("Authenticating with Proxmox cluster...")
            def result = makeHttpCall(authUrl, 'POST', headers, formData)
            
            if (result.data?.ticket) {
                log.info("Successfully authenticated with Proxmox cluster")
                return [
                    ticket: result.data.ticket,
                    csrfToken: result.data.CSRFPreventionToken
                ]
            } else {
                throw new RuntimeException("Authentication failed: No ticket received")
            }
        } catch (Exception e) {
            log.error("Authentication failed: ${e.message}", e)
            throw new RuntimeException("Proxmox authentication failed: ${e.message}")
        }
    }
    
    /**
     * Make authenticated API call to Proxmox
     */
    def callApi(String endpoint, String method = 'GET', Map data = null) {
        def auth = authenticate()
        
        def url = "${cloud.serviceUrl}/api2/json${endpoint}"
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
    
    /**
     * Create VM using cluster credentials
     */
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
    
    /**
     * Get VM status using cluster credentials
     */
    def getVmStatus(String node, String vmId) {
        def endpoint = "/nodes/${node}/qemu/${vmId}/status/current"
        return callApi(endpoint, 'GET')
    }
    
    /**
     * Start VM using cluster credentials
     */
    def startVm(String node, String vmId) {
        log.info("Starting VM ${vmId} on node ${node}")
        def endpoint = "/nodes/${node}/qemu/${vmId}/status/start"
        return callApi(endpoint, 'POST')
    }
    
    /**
     * Stop VM using cluster credentials
     */
    def stopVm(String node, String vmId) {
        log.info("Stopping VM ${vmId} on node ${node}")
        def endpoint = "/nodes/${node}/qemu/${vmId}/status/stop"
        return callApi(endpoint, 'POST')
    }
    
    /**
     * Restart VM using cluster credentials
     */
    def restartVm(String node, String vmId) {
        log.info("Restarting VM ${vmId} on node ${node}")
        def endpoint = "/nodes/${node}/qemu/${vmId}/status/reboot"
        return callApi(endpoint, 'POST')
    }
    
    /**
     * Delete VM using cluster credentials
     */
    def deleteVm(String node, String vmId) {
        log.info("Deleting VM ${vmId} on node ${node}")
        def endpoint = "/nodes/${node}/qemu/${vmId}"
        return callApi(endpoint, 'DELETE')
    }
    
    /**
     * Get cluster nodes - demonstrates cluster-level access
     */
    def getClusterNodes() {
        log.info("Getting cluster nodes using cluster credentials")
        def result = callApi('/cluster/status', 'GET')
        return result.data?.findAll { it.type == 'node' }
    }
    
    /**
     * Get cluster resources
     */
    def getClusterResources() {
        log.info("Getting cluster resources")
        def result = callApi('/cluster/resources', 'GET')
        return result.data
    }
    
    /**
     * Get node information
     */
    def getNodeInfo(String node) {
        def endpoint = "/nodes/${node}/status"
        return callApi(endpoint, 'GET')
    }
    
    /**
     * Get storage information for a node
     */
    def getNodeStorage(String node) {
        def endpoint = "/nodes/${node}/storage"
        return callApi(endpoint, 'GET')
    }
    
    /**
     * Get network interfaces for a node
     */
    def getNodeNetworks(String node) {
        def endpoint = "/nodes/${node}/network"
        return callApi(endpoint, 'GET')
    }
    
    /**
     * Get VM list for a node
     */
    def getNodeVms(String node) {
        def endpoint = "/nodes/${node}/qemu"
        return callApi(endpoint, 'GET')
    }
    
    /**
     * Get VM configuration
     */
    def getVmConfig(String node, String vmId) {
        def endpoint = "/nodes/${node}/qemu/${vmId}/config"
        return callApi(endpoint, 'GET')
    }
    
    /**
     * Update VM configuration
     */
    def updateVmConfig(String node, String vmId, Map config) {
        log.info("Updating VM ${vmId} configuration on node ${node}")
        def endpoint = "/nodes/${node}/qemu/${vmId}/config"
        return callApi(endpoint, 'PUT', config)
    }
    
    /**
     * Clone VM
     */
    def cloneVm(String node, String vmId, Map cloneConfig) {
        log.info("Cloning VM ${vmId} on node ${node}")
        def endpoint = "/nodes/${node}/qemu/${vmId}/clone"
        return callApi(endpoint, 'POST', cloneConfig)
    }
    
    /**
     * Create VM snapshot
     */
    def createSnapshot(String node, String vmId, String snapname, String description = null) {
        log.info("Creating snapshot ${snapname} for VM ${vmId} on node ${node}")
        def endpoint = "/nodes/${node}/qemu/${vmId}/snapshot"
        def data = [snapname: snapname]
        if (description) {
            data.description = description
        }
        return callApi(endpoint, 'POST', data)
    }
    
    /**
     * Delete VM snapshot
     */
    def deleteSnapshot(String node, String vmId, String snapname) {
        log.info("Deleting snapshot ${snapname} for VM ${vmId} on node ${node}")
        def endpoint = "/nodes/${node}/qemu/${vmId}/snapshot/${snapname}"
        return callApi(endpoint, 'DELETE')
    }
    
    /**
     * Get VM snapshots
     */
    def getVmSnapshots(String node, String vmId) {
        def endpoint = "/nodes/${node}/qemu/${vmId}/snapshot"
        return callApi(endpoint, 'GET')
    }
    
    /**
     * Resize VM disk
     */
    def resizeDisk(String node, String vmId, String disk, String size) {
        log.info("Resizing disk ${disk} to ${size} for VM ${vmId} on node ${node}")
        def endpoint = "/nodes/${node}/qemu/${vmId}/resize"
        def data = [disk: disk, size: size]
        return callApi(endpoint, 'PUT', data)
    }
    
    /**
     * Migrate VM to another node
     */
    def migrateVm(String node, String vmId, String targetNode, Map options = [:]) {
        log.info("Migrating VM ${vmId} from ${node} to ${targetNode}")
        def endpoint = "/nodes/${node}/qemu/${vmId}/migrate"
        def data = [target: targetNode] + options
        return callApi(endpoint, 'POST', data)
    }
    
    /**
     * Get cluster version information
     */
    def getVersion() {
        def result = callApi('/version', 'GET')
        return result.data
    }
    
    /**
     * Test cluster connectivity
     */
    def testConnection() {
        try {
            log.info("Testing Proxmox cluster connection...")
            
            // Test basic connectivity first
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
    
    /**
     * Utility method to build form data
     */
    private String buildFormData(Map data) {
        return data.collect { k, v -> 
            URLEncoder.encode(k.toString(), 'UTF-8') + '=' + URLEncoder.encode(v.toString(), 'UTF-8') 
        }.join('&')
    }
    
    /**
     * Validate configuration
     */
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
