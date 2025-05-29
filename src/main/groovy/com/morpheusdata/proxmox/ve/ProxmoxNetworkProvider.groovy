package com.morpheusdata.proxmox.ve

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.providers.NetworkProvider
import com.morpheusdata.core.providers.CloudInitializationProvider
import com.morpheusdata.core.providers.SecurityGroupProvider
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.Network
import com.morpheusdata.model.NetworkServer
import com.morpheusdata.model.NetworkServerType
import com.morpheusdata.model.NetworkSubnet
import com.morpheusdata.model.NetworkType
import com.morpheusdata.model.OptionType
import com.morpheusdata.response.ServiceResponse
import groovy.util.logging.Slf4j

@Slf4j
class ProxmoxNetworkProvider implements NetworkProvider, CloudInitializationProvider {

        public static final String CLOUD_PROVIDER_CODE = 'proxmox-ve'

	ProxmoxVePlugin plugin
	MorpheusContext morpheus
	SecurityGroupProvider securityGroupProvider

        final String code = 'proxmox-ve-network'
	final String name = 'Proxmox Network'
	final String description = 'Proxmox Network Provider'

        ProxmoxNetworkProvider(ProxmoxVePlugin plugin, MorpheusContext morpheusContext) {
                this.plugin = plugin
                this.morpheus = morpheusContext
        }

        @Override
        String getCode() {
                return 'proxmox-ve-network'
        }

        @Override
        String getNetworkServerTypeCode() {
                return 'proxmox-ve-network'
        }

	@Override
	String getCloudProviderCode() {
		return CLOUD_PROVIDER_CODE
	}

	@Override
        Collection<NetworkType> getNetworkTypes() {
                Collection<NetworkType> networkTypes = []

                networkTypes << new NetworkType([
                        code              : 'proxmox-ve-bridge-network',
                        name              : 'Proxmox Bridge',
                        externalType      : 'LinuxBridge',
                        cidrEditable      : true,
                        dhcpServerEditable: false,
                        dnsEditable       : true,
                        gatewayEditable   : true,
                        vlanIdEditable    : false,
                        canAssignPool     : true
                ])

                networkTypes << new NetworkType([
                        code              : 'proxmox-ve-vlan-network',
                        name              : 'Proxmox VLAN',
                        externalType      : 'VLAN',
                        cidrEditable      : true,
                        dhcpServerEditable: false,
                        dnsEditable       : true,
                        gatewayEditable   : true,
                        vlanIdEditable    : true,
                        canAssignPool     : true,
                        hasSecurityGroups : false
                ])

                networkTypes << new NetworkType([
                        code              : 'proxmox-ve-bond-network',
                        name              : 'Proxmox Bond',
                        externalType      : 'Bond',
                        cidrEditable      : true,
                        dhcpServerEditable: false,
                        dnsEditable       : true,
                        gatewayEditable   : true,
                        vlanIdEditable    : false,
                        canAssignPool     : true
                ])

                return networkTypes
        }

	@Override
	Collection<OptionType> getOptionTypes() {
		return null
	}

	@Override
	Collection<OptionType> getSecurityGroupOptionTypes() {
		return []
	}

	@Override
	ServiceResponse initializeProvider(Cloud cloud) {
		log.info("Initializing network provider for ${cloud.name}")
		ServiceResponse rtn = ServiceResponse.prepare()
		try {
			NetworkServer networkServer = new NetworkServer(
				name: cloud.name,
				type: new NetworkServerType(code: "proxmox-ve-network")
			)
			morpheus.integration.registerCloudIntegration(cloud.id, networkServer).blockingGet()
			rtn.success = true
		} catch (Exception e) {
			rtn.success = false
			log.error("initializeProvider error: {}", e, e)
		}

		return rtn
	}

	@Override
	ServiceResponse deleteProvider(Cloud cloud) {
		log.info("Deleting network provider for ${cloud.name}")
		ServiceResponse rtn = ServiceResponse.prepare()
		try {
			NetworkServer networkServer = new NetworkServer(
				name: cloud.name,
				type: new NetworkServerType(code: "proxmox-ve-network")
			)
			morpheus.integration.deleteCloudIntegration(cloud.id, networkServer).blockingGet()
			rtn.success = true
		} catch (Exception e) {
			rtn.success = false
			log.error("deleteProvider error: {}", e, e)
		}

		return rtn
	}

        @Override
        ServiceResponse createNetwork(Network network, Map opts) {
                try {
                        if (!network || !network.cloud) {
                                return ServiceResponse.error("Invalid network or cloud configuration")
                        }

                        def apiClient = new ProxmoxApiClient(morpheus, network.cloud, plugin)
                        def targetNode = opts?.targetNode ?: getDefaultNode(apiClient)
                        def result

                        switch (network.type?.code) {
                                case 'proxmox-ve-vlan-network':
                                        result = createVlanNetwork(apiClient, network, targetNode, opts)
                                        break
                                case 'proxmox-ve-bond-network':
                                        result = createBondNetwork(apiClient, network, targetNode, opts)
                                        break
                                case 'proxmox-ve-bridge-network':
                                default:
                                        result = createBridgeNetwork(apiClient, network, targetNode, opts)
                                        break
                        }

                        if (result) {
                                network.externalId = network.name
                                network.configMap = network.configMap ?: [:]
                                network.configMap.targetNode = targetNode
                                return ServiceResponse.success(network)
                        } else {
                                return ServiceResponse.error("Failed to create network: ${network.name}")
                        }
                } catch (Exception e) {
                        log.error("Network creation failed: ${e.message}", e)
                        return ServiceResponse.error("Network creation failed: ${e.message}")
                }
        }

        private def createVlanNetwork(apiClient, Network network, String targetNode, Map opts) {
                def vlanId = network.vlanId ?: opts.vlanId
                def parentBridge = opts.parentBridge ?: 'vmbr0'

                if (!vlanId) {
                        throw new RuntimeException("VLAN ID is required for VLAN networks")
                }

                def config = [
                        ipAddress: network.cidr ? extractIpFromCidr(network.cidr) : null,
                        netmask  : network.cidr ? extractNetmaskFromCidr(network.cidr) : null,
                        gateway  : network.gateway,
                        autostart: 1
                ]

                return apiClient.createVlan(targetNode, parentBridge, vlanId as Integer, config)
        }

        private def createBondNetwork(apiClient, Network network, String targetNode, Map opts) {
                def slaves = opts.bondSlaves ?: []
                if (!slaves) {
                        throw new RuntimeException("Bond slaves are required for bond networks")
                }

                def config = [
                        bondMode : opts.bondMode ?: 'active-backup',
                        ipAddress: network.cidr ? extractIpFromCidr(network.cidr) : null,
                        netmask  : network.cidr ? extractNetmaskFromCidr(network.cidr) : null,
                        gateway  : network.gateway,
                        autostart: 1
                ]

                return apiClient.createBond(targetNode, network.name, slaves, config)
        }

        private def createBridgeNetwork(apiClient, Network network, String targetNode, Map opts) {
                def config = [
                        ports     : opts.bridgePorts ?: 'none',
                        stp       : opts.enableStp ?: false,
                        vlanAware : opts.vlanAware ?: false,
                        ipAddress : network.cidr ? extractIpFromCidr(network.cidr) : null,
                        netmask   : network.cidr ? extractNetmaskFromCidr(network.cidr) : null,
                        gateway   : network.gateway,
                        autostart : 1
                ]

                if (opts.vlanAware) {
                        config.vlanRange = opts.vlanRange ?: '2-4094'
                }

                return apiClient.createAdvancedBridge(targetNode, network.name, config)
        }

        @Override
        ServiceResponse<Network> updateNetwork(Network network, Map opts) {
                try {
                        if (!network || !network.cloud) {
                                return ServiceResponse.error("Invalid network configuration")
                        }

                        def apiClient = new ProxmoxApiClient(morpheus, network.cloud, plugin)
                        def targetNode = opts?.targetNode ?: network.configMap?.targetNode ?: getDefaultNode(apiClient)

                        def networkConfig = [
                                iface    : network.name,
                                type     : 'bridge',
                                autostart: 1
                        ]

                        if (network.cidr) {
                                networkConfig.address = extractIpFromCidr(network.cidr)
                                networkConfig.netmask = extractNetmaskFromCidr(network.cidr)
                        }

                        if (network.gateway) {
                                networkConfig.gateway = network.gateway
                        }

                        def result = apiClient.callApi("/nodes/${targetNode}/network/${network.externalId}", 'PUT', networkConfig)

                        if (result) {
                                return ServiceResponse.success(network)
                        } else {
                                return ServiceResponse.error("Failed to update network")
                        }
                } catch (Exception e) {
                        log.error("Network update failed: ${e.message}", e)
                        return ServiceResponse.error("Network update failed: ${e.message}")
                }
        }

        @Override
        ServiceResponse deleteNetwork(Network network, Map opts) {
                try {
                        if (!network || !network.cloud || !network.externalId) {
                                return ServiceResponse.error("Invalid network configuration")
                        }

                        def apiClient = new ProxmoxApiClient(morpheus, network.cloud, plugin)
                        def targetNode = network.configMap?.targetNode ?: getDefaultNode(apiClient)

                        def result = apiClient.callApi("/nodes/${targetNode}/network/${network.externalId}", 'DELETE')

                        if (result) {
                                return ServiceResponse.success()
                        } else {
                                return ServiceResponse.error("Failed to delete network")
                        }
                } catch (Exception e) {
                        log.error("Network deletion failed: ${e.message}", e)
                        return ServiceResponse.error("Network deletion failed: ${e.message}")
                }
        }
	
	@Override
        ServiceResponse createSubnet(NetworkSubnet subnet, Network network, Map opts) {
                try {
                        if (!subnet || !network || !network.cloud) {
                                return ServiceResponse.error("Invalid subnet or network configuration")
                        }

                        log.info("Creating subnet ${subnet.name} in network ${network.name}")

                        def apiClient = new ProxmoxApiClient(morpheus, network.cloud, plugin)
                        def targetNode = network.configMap?.targetNode ?: getDefaultNode(apiClient)

                        if (subnet.type?.code == 'proxmox-ve-vlan-subnet') {
                                def vlanId = subnet.vlanId ?: opts.vlanId
                                if (!vlanId) {
                                        return ServiceResponse.error("VLAN ID is required for VLAN subnets")
                                }

                                def parentInterface = network.externalId

                                def config = [
                                        ipAddress: subnet.cidr ? extractIpFromCidr(subnet.cidr) : null,
                                        netmask  : subnet.cidr ? extractNetmaskFromCidr(subnet.cidr) : null,
                                        gateway  : subnet.gateway,
                                        autostart: 1
                                ]

                                def result = apiClient.createVlan(targetNode, parentInterface, vlanId as Integer, config)

                                if (result) {
                                        subnet.externalId = "${parentInterface}.${vlanId}"
                                        subnet.configMap = subnet.configMap ?: [:]
                                        subnet.configMap.targetNode = targetNode
                                        subnet.configMap.parentInterface = parentInterface
                                        return ServiceResponse.success(subnet)
                                } else {
                                        return ServiceResponse.error("Failed to create VLAN subnet")
                                }
                        }

                        return ServiceResponse.error("Subnet type not supported: ${subnet.type?.code}")
                } catch (Exception e) {
                        log.error("Subnet creation failed: ${e.message}", e)
                        return ServiceResponse.error("Subnet creation failed: ${e.message}")
                }
        }

        @Override
        ServiceResponse updateSubnet(NetworkSubnet subnet, Network network, Map opts) {
                try {
                        if (!subnet || !network || !network.cloud || !subnet.externalId) {
                                return ServiceResponse.error("Invalid subnet configuration")
                        }

                        def apiClient = new ProxmoxApiClient(morpheus, network.cloud, plugin)
                        def targetNode = subnet.configMap?.targetNode ?: getDefaultNode(apiClient)

                        def config = [:]
                        if (subnet.cidr) {
                                config.address = extractIpFromCidr(subnet.cidr)
                                config.netmask = extractNetmaskFromCidr(subnet.cidr)
                        }
                        if (subnet.gateway) {
                                config.gateway = subnet.gateway
                        }

                        def result = apiClient.updateVlan(targetNode, subnet.externalId, config)

                        if (result) {
                                return ServiceResponse.success(subnet)
                        } else {
                                return ServiceResponse.error("Failed to update subnet")
                        }
                } catch (Exception e) {
                        log.error("Subnet update failed: ${e.message}", e)
                        return ServiceResponse.error("Subnet update failed: ${e.message}")
                }
        }
	
	@Override
	ServiceResponse deleteSubnet(NetworkSubnet subnet, Network network, Map opts) {
                try {
                        if (!subnet || !network || !network.cloud || !subnet.externalId) {
                                return ServiceResponse.error("Invalid subnet configuration")
                        }

                        def apiClient = new ProxmoxApiClient(morpheus, network.cloud, plugin)
                        def targetNode = subnet.configMap?.targetNode ?: getDefaultNode(apiClient)

                        def result = apiClient.callApi("/nodes/${targetNode}/network/${subnet.externalId}", 'DELETE')

                        if (result) {
                                return ServiceResponse.success()
                        } else {
                                return ServiceResponse.error("Failed to delete subnet")
                        }
                } catch (Exception e) {
                        log.error("Subnet deletion failed: ${e.message}", e)
                        return ServiceResponse.error("Subnet deletion failed: ${e.message}")
                }
	}
	
	@Override
	Collection getRouterTypes() {
		return []
	}

	@Override
	ServiceResponse<Network> prepareNetwork(Network network, Map opts) {
		log.info("Preparing network: ${network.name}")
		return ServiceResponse.success(network);
	}

        @Override
        ServiceResponse validateNetwork(Network network, Map opts) {
                try {
                        def validation = [:]
                        def errors = []

                        if (!network.name) {
                                errors << "Network name is required"
                        }

                        if (network.type?.code == 'proxmox-ve-vlan-network' && !network.vlanId && !opts.vlanId) {
                                errors << "VLAN ID is required for VLAN networks"
                        }

                        if (network.type?.code == 'proxmox-ve-bond-network' && !opts.bondSlaves) {
                                errors << "Bond slaves are required for bond networks"
                        }

                        if (network.cidr && !isValidCidr(network.cidr)) {
                                errors << "Invalid CIDR notation"
                        }

                        if (errors) {
                                return ServiceResponse.error("Validation failed", null, [errors: errors])
                        }

                        return ServiceResponse.success()
                } catch (Exception e) {
                        log.error("Network validation failed: ${e.message}", e)
                        return ServiceResponse.error("Network validation failed: ${e.message}")
                }
        }

        private String getDefaultNode(apiClient) {
                try {
                        def nodes = apiClient.getClusterNodes()
                        return nodes?.first()?.node ?: 'pve'
                } catch (Exception e) {
                        log.warn("Could not get cluster nodes, using 'pve' as default: ${e.message}")
                        return 'pve'
                }
        }

        private String extractIpFromCidr(String cidr) {
                if (!cidr)
                        return null
                return cidr.split('/')[0]
        }

        private String extractNetmaskFromCidr(String cidr) {
                if (!cidr)
                        return null
                def parts = cidr.split('/')
                if (parts.size() != 2)
                        return null
                try {
                        int prefix = parts[1].toInteger()
                        long mask = (0xffffffff << (32 - prefix)) & 0xffffffff
                        return [
                                (mask >>> 24) & 0xff,
                                (mask >>> 16) & 0xff,
                                (mask >>> 8) & 0xff,
                                mask & 0xff
                        ].join('.')
                } catch (Exception e) {
                        log.warn("Invalid CIDR prefix: ${parts[1]}")
                        return null
                }
        }

        private boolean isValidCidr(String cidr) {
                try {
                        def parts = cidr.split('/')
                        if (parts.size() != 2) return false
                        
                        def ip = parts[0]
                        def prefix = parts[1].toInteger()
                        
                        if (prefix < 0 || prefix > 32) return false
                        
                        def ipParts = ip.split('\\.')
                        if (ipParts.size() != 4) return false
                        
                        ipParts.each { part ->
                                int octet = part.toInteger()
                                if (octet < 0 || octet > 255) return false
                        }
                        
                        return true
                } catch (Exception e) {
                        return false
                }
        }
}