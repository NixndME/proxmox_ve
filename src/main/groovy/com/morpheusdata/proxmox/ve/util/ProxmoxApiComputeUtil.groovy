package com.morpheusdata.proxmox.ve.util

import com.morpheusdata.core.util.HttpApiClient
import com.morpheusdata.response.ServiceResponse
import groovy.util.logging.Slf4j
import org.apache.http.entity.ContentType
import groovy.json.JsonSlurper


@Slf4j
class ProxmoxApiComputeUtil {

    //static final String API_BASE_PATH = "/api2/json"
    static final Long API_CHECK_WAIT_INTERVAL = 2000


    /*
    static setCloudInitData(HttpApiClient client, Map authConfig, String node, String vmId, String ciData) {
        log.debug("resizeVMCompute")


        try {
            def tokenCfg = getApiV2Token(authConfig.username, authConfig.password, authConfig.apiUrl).data
            def opts = [
                    headers  : [
                            'Content-Type'       : 'application/json',
                            'Cookie'             : "PVEAuthCookie=$tokenCfg.token",
                            'CSRFPreventionToken': tokenCfg.csrfToken
                    ],
                    body     : [
                            vmid: vmId,
                            node: node,
                            vcpus: cpu,
                            memory: ramValue
                    ],
                    contentType: ContentType.APPLICATION_JSON,
                    ignoreSSL: true
            ]

            log.debug("Setting VM Compute Size $vmId on node $node...")
            log.debug("POST path is: $authConfig.apiUrl${authConfig.v2basePath}/nodes/$node/qemu/$vmId/config")
            log.debug("POST body is: $opts.body")
            sleep(10000)
            def results = client.callJsonApi(
                    (String) authConfig.apiUrl,
                    "${authConfig.v2basePath}/nodes/$node/qemu/$vmId/config",
                    null, null,
                    new HttpApiClient.RequestOptions(opts),
                    'POST'
            )

            return results
        } catch (e) {
            log.error "Error Provisioning VM: ${e}", e
            return ServiceResponse.error("Error Provisioning VM: ${e}")
        }
    }
*/


    static resizeVMCompute(HttpApiClient client, Map authConfig, String node, String vmId, Long cpu, Long ram) {
        log.debug("resizeVMCompute")
        Long ramValue = ram / 1024 / 1024

        try {
            def tokenCfg = getApiV2Token(authConfig).data
            def opts = [
                    headers  : [
                            'Content-Type'       : 'application/json',
                            'Cookie'             : "PVEAuthCookie=$tokenCfg.token",
                            'CSRFPreventionToken': tokenCfg.csrfToken
                    ],
                    body     : [
                            vmid  : vmId,
                            node  : node,
                            vcpus : cpu,
                            memory: ramValue,
                            net0  : "bridge=vmbr0,model=e1000e"
                    ],
                    contentType: ContentType.APPLICATION_JSON,
                    ignoreSSL: true
            ]

            log.debug("Setting VM Compute Size $vmId on node $node...")
            log.debug("POST path is: $authConfig.apiUrl${authConfig.v2basePath}/nodes/$node/qemu/$vmId/config")

            def results = client.callJsonApi(
                    (String) authConfig.apiUrl,
                    "${authConfig.v2basePath}/nodes/$node/qemu/$vmId/config",
                    null, null,
                    new HttpApiClient.RequestOptions(opts),
                    'POST'
            )

            return results
        } catch (e) {
            log.error "Error Provisioning VM: ${e}", e
            return ServiceResponse.error("Error Provisioning VM: ${e}")
        }
    }


    static startVM(HttpApiClient client, Map authConfig, String nodeId, String vmId) {
        log.debug("startVM")
        return actionVMStatus(client, authConfig, nodeId, vmId, "start")
    }

    static rebootVM(HttpApiClient client, Map authConfig, String nodeId, String vmId) {
        log.debug("rebootVM")
        return actionVMStatus(client, authConfig, nodeId, vmId, "reboot")
    }

    static shutdownVM(HttpApiClient client, Map authConfig, String nodeId, String vmId) {
        log.debug("shutdownVM")
        return actionVMStatus(client, authConfig, nodeId, vmId, "shutdown")
    }

    static stopVM(HttpApiClient client, Map authConfig, String nodeId, String vmId) {
        log.debug("stopVM")
        return actionVMStatus(client, authConfig, nodeId, vmId, "stop")
    }

    static resetVM(HttpApiClient client, Map authConfig, String nodeId, String vmId) {
        log.debug("resetVM")
        return actionVMStatus(client, authConfig, nodeId, vmId, "reset")
    }


    static actionVMStatus(HttpApiClient client, Map authConfig, String nodeId, String vmId, String action) {

        try {
            def tokenCfg = getApiV2Token(authConfig).data
            def opts = [
                    headers  : [
                            'Content-Type'       : 'application/json',
                            'Cookie'             : "PVEAuthCookie=$tokenCfg.token",
                            'CSRFPreventionToken': tokenCfg.csrfToken
                    ],
                    body     : [
                            vmid: vmId,
                            node: nodeId
                    ],
                    contentType: ContentType.APPLICATION_JSON,
                    ignoreSSL: true
            ]

            log.debug("Post path is: $authConfig.apiUrl${authConfig.v2basePath}/nodes/$nodeId/qemu/$vmId/status/$action/")
            def results = client.callJsonApi(
                    (String) authConfig.apiUrl,
                    "${authConfig.v2basePath}/nodes/$nodeId/qemu/$vmId/status/$action/",
                    null, null,
                    new HttpApiClient.RequestOptions(opts),
                    'POST'
            )

            return results
        } catch (e) {
            log.error "Error performing $action on VM: ${e}", e
            return ServiceResponse.error("Error performing $action on VM: ${e}")
        }
    }


    static destroyVM(HttpApiClient client, Map authConfig, String nodeId, String vmId) {
        log.debug("destroyVM")
        try {
            def tokenCfg = getApiV2Token(authConfig).data
            def opts = [
                    headers  : [
                            'Content-Type'       : 'application/json',
                            'Cookie'             : "PVEAuthCookie=$tokenCfg.token",
                            'CSRFPreventionToken': tokenCfg.csrfToken
                    ],
                    body: null,
                    ignoreSSL: true,
                    contentType: ContentType.APPLICATION_JSON,
            ]

            log.debug("Delete Opts: $opts")
            log.debug("Delete path is: $authConfig.apiUrl${authConfig.v2basePath}/nodes/$nodeId/qemu/$vmId/")

            def results = client.callJsonApi(
                    (String) authConfig.apiUrl,
                    "${authConfig.v2basePath}/nodes/$nodeId/qemu/$vmId/",
                    new HttpApiClient.RequestOptions(opts),
                    'DELETE'
            )

            log.debug("VM Delete Response Details: ${results.toMap()}")
            if(results?.statusCode < 200 || results?.statusCode >= 300) {
                def rtn = results instanceof ServiceResponse ? results : new ServiceResponse(success:false)
                rtn.success = false
                rtn.msg = "Destroy VM failed with status ${results?.statusCode}"
                log.error("Destroy VM request failed - status ${results?.statusCode}")
                return rtn
            }
            return results

        //TODO - check for non 200 response
        } catch (e) {
            log.error "Error Destroying VM: ${e}", e
            return ServiceResponse.error("Error Destroying VM: ${e}")
        }
    }


    static createImageTemplate(HttpApiClient client, Map authConfig, String imageName, String nodeId, int cpu, Long ram, String sourceUri = null) {
        log.debug("createImage: $imageName")

        def rtn = new ServiceResponse(success: true)
        def nextId = callListApiV2(client, "cluster/nextid", authConfig).data
        log.debug("Next VM Id is: $nextId")

        try {
            def tokenCfg = getApiV2Token(authConfig).data
            rtn.data = []
            def opts = [
                    headers  : [
                            'Content-Type'       : 'application/json',
                            'Cookie'             : "PVEAuthCookie=$tokenCfg.token",
                            'CSRFPreventionToken': tokenCfg.csrfToken
                    ],
                    body     : [
                            vmid: nextId,
                            node: nodeId,
                            name: imageName,
                            template: true
                    ],
                    contentType: ContentType.APPLICATION_JSON,
                    ignoreSSL: true
            ]

            log.debug("Creating blank template for attaching qcow2...")
            log.debug("Path is: $authConfig.apiUrl${authConfig.v2basePath}/nodes/$nodeId/qemu/")
            def results = client.callJsonApi(
                    (String) authConfig.apiUrl,
                    "${authConfig.v2basePath}/nodes/$nodeId/qemu/",
                    null, null,
                    new HttpApiClient.RequestOptions(opts),
                    'POST'
            )

            def resultData = new JsonSlurper().parseText(results.content)
            if (results?.success && !results?.hasErrors()) {
                rtn.success = true
                rtn.data = resultData
                rtn.data.templateId = nextId
            } else {
                rtn.msg = "Template create failed: $results.data $results $results.errorCode $results.content"
                rtn.success = false
            }
        } catch (e) {
            log.error "Error Provisioning VM: ${e}", e
            return ServiceResponse.error("Error Provisioning VM: ${e}")
        }
        return rtn
    }


    static ServiceResponse waitForCloneToComplete(HttpApiClient client, Map authConfig, String templateId, String vmId, String nodeId, Long timeoutInSec) {
        Long timeout = timeoutInSec * 1000
        Long duration = 0
        log.debug("waitForCloneToComplete: $templateId")

        try {
            def tokenCfg = getApiV2Token(authConfig).data
            def opts = [
                    headers: [
                            'Content-Type': 'application/json',
                            'Cookie': "PVEAuthCookie=$tokenCfg.token",
                            'CSRFPreventionToken': tokenCfg.csrfToken
                    ],
                    contentType: ContentType.APPLICATION_JSON,
                    ignoreSSL: true
            ]

            log.debug("Checking VM Status after clone template $templateId to VM $vmId on node $nodeId")
            log.debug("Path is: $authConfig.apiUrl${authConfig.v2basePath}/nodes/$nodeId/qemu/$vmId/config")

            while (duration < timeout) {
                log.info("Checking VM $vmId status on node $nodeId")
                def results = client.callJsonApi(
                        (String) authConfig.apiUrl,
                        "${authConfig.v2basePath}/nodes/$nodeId/qemu/$vmId/config",
                        null, null,
                        new HttpApiClient.RequestOptions(opts),
                        'GET'
                )

                if (!results.success) {
                    log.error("Error checking VM clone result status.")
                    return results
                }

                def resultData = new JsonSlurper().parseText(results.content)
                log.info("Check results: $resultData")
                if (!resultData.data.containsKey("lock")) {
                    return results
                } else {
                    log.info("VM Still Locked, wait ${API_CHECK_WAIT_INTERVAL}ms and check again...")
                }
                sleep(API_CHECK_WAIT_INTERVAL)
                duration += API_CHECK_WAIT_INTERVAL
            }
            return new ServiceResponse(success: false, msg: "Timeout", data: "Timeout")
        } catch(e) {
            log.error "Error Checking VM Clone Status: ${e}", e
            return ServiceResponse.error("Error Checking VM Clone Status: ${e}")
        }
    }


    static cloneTemplate(HttpApiClient client, Map authConfig, String templateId, String name, String nodeId, Long vcpus, Long ram) {
        log.debug("cloneTemplate: $templateId")

        def rtn = new ServiceResponse(success: true)
        def nextId = callListApiV2(client, "cluster/nextid", authConfig).data
        log.debug("Next VM Id is: $nextId")

        try {
            def tokenCfg = getApiV2Token(authConfig).data
            rtn.data = []
            def opts = [
                    headers: [
                            'Content-Type': 'application/json',
                            'Cookie': "PVEAuthCookie=$tokenCfg.token",
                            'CSRFPreventionToken': tokenCfg.csrfToken
                    ],
                    body: [
                            newid: nextId,
                            node: nodeId,
                            vmid: templateId,
                            name: name,
                            full: true
                    ],
                    contentType: ContentType.APPLICATION_JSON,
                    ignoreSSL: true
            ]

            log.debug("Cloning template $templateId to VM $name($nextId) on node $nodeId")
            log.debug("Path is: $authConfig.apiUrl${authConfig.v2basePath}/nodes/$nodeId/qemu/$templateId/clone")
            log.debug("Body data is: $opts.body")
            def results = client.callJsonApi(
                    (String) authConfig.apiUrl,
                    "${authConfig.v2basePath}/nodes/$nodeId/qemu/$templateId/clone",
                    null, null,
                    new HttpApiClient.RequestOptions(opts),
                    'POST'
            )

            def resultData = new JsonSlurper().parseText(results.content)

            if(results?.success && !results?.hasErrors()) {
                rtn.success = true
                rtn.data = resultData

                ServiceResponse cloneWaitResult = waitForCloneToComplete(new HttpApiClient(), authConfig, templateId, nextId, nodeId, 3600L)

                if (!cloneWaitResult?.success) {
                    return ServiceResponse.error("Error Provisioning VM. Wait for clone error: ${cloneWaitResult}")
                }

                log.info("Resizing newly cloned VM. Spec: CPU $vcpus, RAM $ram")
                ServiceResponse rtnResize = resizeVMCompute(new HttpApiClient(), authConfig, nodeId, nextId, vcpus, ram)

                if (!rtnResize?.success) {
                    return ServiceResponse.error("Error Sizing VM Compute. Resize compute error: ${rtnResize}")
                }

                rtn.data.vmId = nextId
            } else {
                rtn.msg = "Provisioning failed: ${results.toMap()}"
                rtn.success = false
            }
        } catch(e) {
            log.error "Error Provisioning VM: ${e}", e
            return ServiceResponse.error("Error Provisioning VM: ${e}")
        }
        return rtn
    }


    static ServiceResponse listProxmoxDatastores(HttpApiClient client, Map authConfig) {
        log.debug("listProxmoxDatastores...")

        var allowedDatastores = ["rbd", "cifs", "zfspool", "nfs", "lvmthin", "lvm"]

        ServiceResponse datastoreResults = callListApiV2(client, "storage", authConfig)
        List<Map> validDatastores = []
        String queryNode = ""
        String randomNode = null
        for (ds in datastoreResults.data) {
            if (allowedDatastores.contains(ds.type)) {
                if (ds.containsKey("nodes")) {
                    //some pools don't belong to any node, but api path needs node for status details
                    queryNode = ((String) ds.nodes).split(",")[0]
                } else {
                    if (!randomNode) {
                        randomNode = listProxmoxHypervisorHosts(client, authConfig).data.get(0).node
                    }
                    queryNode = randomNode
                }

                Map dsInfo = callListApiV2(client, "nodes/${queryNode}/storage/${ds.storage}/status", authConfig).data
                ds.total = dsInfo.total
                ds.avail = dsInfo.avail
                ds.used = dsInfo.used
                ds.enabled = dsInfo.enabled

                validDatastores += ds
            } else {
                log.warn("Storage ${ds} ignored...")
            }
        }
        datastoreResults.data = validDatastores
        return datastoreResults
    }


    static ServiceResponse listProxmoxNetworks(HttpApiClient client, Map authConfig) {
        log.debug("listProxmoxNetworks...")

        Collection<Map> networks = []
        Set<String> ifaces = []
        ServiceResponse hosts = listProxmoxHypervisorHosts(client, authConfig)

        hosts.data.each {
            ServiceResponse hostNetworks = callListApiV2(client, "nodes/${it.node}/network", authConfig)
            hostNetworks.data.each { Map network ->
                if (network?.type == 'bridge' && !ifaces.contains(network?.iface)) {
                    network.networkAddress = ""
                    if (network.containsKey("cidr") && network['cidr']) {
                        network.networkAddress = ProxmoxMiscUtil.getNetworkAddress(network.cidr)
                    }
                    networks << (network)
                    ifaces << network.iface
                }
            }
        }

        return new ServiceResponse(success: true, data: networks)
    }


    static ServiceResponse listTemplates(HttpApiClient client, Map authConfig) {
        log.debug("API Util listTemplates")
        def vms = []
        def qemuVMs = callListApiV2(client, "cluster/resources", authConfig)
        qemuVMs.data.each { Map vm ->
            if (vm?.template == 1 && vm?.type == "qemu") {
                vm.ip = "0.0.0.0"
                def vmCPUInfo = callListApiV2(client, "nodes/$vm.node/qemu/$vm.vmid/config", authConfig)
                vm.maxCores = (vmCPUInfo?.data?.data?.sockets?.toInteger() ?: 0) * (vmCPUInfo?.data?.data?.cores?.toInteger() ?: 0)
                vm.coresPerSocket = vmCPUInfo?.data?.data?.cores?.toInteger() ?: 0
                vms << vm
            }
        }
        return new ServiceResponse(success: true, data: vms)
    }


    static ServiceResponse listVMs(HttpApiClient client, Map authConfig) {
        log.debug("API Util listVMs")
        def vms = []
        def qemuVMs = callListApiV2(client, "cluster/resources", authConfig)
        qemuVMs.data.each { Map vm ->
            if (vm?.template == 0 && vm?.type == "qemu") {
                def vmAgentInfo = callListApiV2(client, "nodes/$vm.node/qemu/$vm.vmid/agent/network-get-interfaces", authConfig)
                vm.ip = "0.0.0.0"
                if (vmAgentInfo.success) {
                    def results = vmAgentInfo.data?.result
                    results.each {
                        if (it."ip-address-type" == "ipv4" && it."ip-address" != "127.0.0.1" && vm.ip == "0.0.0.0") {
                            vm.ip = it."ip-address"
                        }
                    }
                }
                def vmCPUInfo = callListApiV2(client, "nodes/$vm.node/qemu/$vm.vmid/config", authConfig)
                vm.maxCores = (vmCPUInfo?.data?.data?.sockets?.toInteger() ?: 0) * (vmCPUInfo?.data?.data?.cores?.toInteger() ?: 0)
                vm.coresPerSocket = vmCPUInfo?.data?.data?.cores?.toInteger() ?: 0
                vms << vm
            }
        }
        return new ServiceResponse(success: true, data: vms)
    }
    

    static ServiceResponse listProxmoxHypervisorHosts(HttpApiClient client, Map authConfig) {
        log.debug("listProxmoxHosts...")

        def nodes = callListApiV2(client, "nodes", authConfig).data
        nodes.each {
            def nodeNetworkInfo = callListApiV2(client, "nodes/$it.node/network", authConfig)
            def ipAddress = nodeNetworkInfo.data[0].address ?: nodeNetworkInfo.data[1].address
            it.ipAddress = ipAddress
        }

        return new ServiceResponse(success: true, data: nodes)
    }
    
    
    private static ServiceResponse callListApiV2(HttpApiClient client, String path, Map authConfig) {
        log.debug("callListApiV2: path: ${path}")

        def tokenCfg = getApiV2Token(authConfig).data
        def rtn = new ServiceResponse(success: false)
        try {
            rtn.data = []
            def opts = new HttpApiClient.RequestOptions(
                    headers: [
                        'Content-Type': 'application/json',
                        'Cookie': "PVEAuthCookie=$tokenCfg.token",
                        'CSRFPreventionToken': tokenCfg.csrfToken
                    ],
                    contentType: ContentType.APPLICATION_JSON,
                    ignoreSSL: true
            )
            def results = client.callJsonApi(authConfig.apiUrl, "${authConfig.v2basePath}/${path}", null, null, opts, 'GET')
            def resultData = results.toMap().data.data
            log.debug("callListApiV2 results: ${resultData}")
            if(results?.success && !results?.hasErrors()) {
                rtn.success = true
                rtn.data = resultData
            } else {
                if(!rtn.success) {
                    rtn.msg = results.data + results.errors
                    rtn.success = false
                }
            }
        } catch(e) {
            log.error "Error in callListApiV2: ${e}", e
            rtn.msg = "Error in callListApiV2: ${e}"
            rtn.success = false
        }
        return rtn
    }


    private static ServiceResponse getApiV2Token(Map authConfig) {
        def path = "access/ticket"
        log.debug("getApiV2Token: path: ${path}")
        HttpApiClient client = new HttpApiClient()

        def rtn = new ServiceResponse(success: false)
        try {

            def encUid = URLEncoder.encode((String) authConfig.username, "UTF-8")
            def encPwd = URLEncoder.encode((String) authConfig.password, "UTF-8")
            def bodyStr = "username=" + "$encUid" + "&password=$encPwd"

            HttpApiClient.RequestOptions opts = new HttpApiClient.RequestOptions(
                    headers: ['Content-Type':'application/x-www-form-urlencoded'],
                    body: bodyStr,
                    contentType: ContentType.APPLICATION_FORM_URLENCODED,
                    ignoreSSL: true
            )
            def results = client.callJsonApi(authConfig.apiUrl,"${authConfig.v2basePath}/${path}", opts, 'POST')

            log.debug("getApiV2Token API request results: ${results.toMap()}")
            if(results?.success && !results?.hasErrors()) {
                rtn.success = true
                def tokenData = results.data.data
                rtn.data = [csrfToken: tokenData.CSRFPreventionToken, token: tokenData.ticket]

            } else {
                rtn.success = false
                rtn.msg = "Error retrieving token: $results.data"
                log.error("Error retrieving token: $results.data")
            }
            return rtn
        } catch(e) {
            log.error "Error in getApiV2Token: ${e}", e
            rtn.msg = "Error in getApiV2Token: ${e}"
            rtn.success = false
        }
        return rtn
    }

/*    private static ServiceResponse getApiV2Token(String uid, String pwd, String baseUrl) {
        def path = "access/ticket"
        log.debug("getApiV2Token: path: ${path}")
        HttpApiClient client = new HttpApiClient()

        def rtn = new ServiceResponse(success: false)
        try {

            def encUid = URLEncoder.encode((String) uid, "UTF-8")
            def encPwd = URLEncoder.encode((String) pwd, "UTF-8")
            def bodyStr = "username=" + "$encUid" + "&password=$encPwd"

            HttpApiClient.RequestOptions opts = new HttpApiClient.RequestOptions(
                    headers: ['Content-Type':'application/x-www-form-urlencoded'],
                    body: bodyStr,
                    contentType: ContentType.APPLICATION_FORM_URLENCODED,
                    ignoreSSL: true
            )
            def results = client.callJsonApi(baseUrl,"${API_BASE_PATH}/${path}", opts, 'POST')

            log.debug("getApiV2Token API request results: ${results.toMap()}")
            if(results?.success && !results?.hasErrors()) {
                rtn.success = true
                def tokenData = results.data.data
                rtn.data = [csrfToken: tokenData.CSRFPreventionToken, token: tokenData.ticket]

            } else {
                rtn.success = false
                rtn.msg = "Error retrieving token: $results.data"
                log.error("Error retrieving token: $results.data")
            }
            return rtn
        } catch(e) {
            log.error "Error in getApiV2Token: ${e}", e
            rtn.msg = "Error in getApiV2Token: ${e}"
            rtn.success = false
        }
        return rtn
    }
    */
}
