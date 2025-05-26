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

	/**
	 * The CloudProvider code that this NetworkProvider should be attached to.
	 * When this NetworkProvider is registered with Morpheus, all Clouds that match this code will have a
	 * NetworkServer of this type attached to them. Network actions will then be handled via this provider.
	 * @return String Code of the Cloud type
	 */
	@Override
	String getCloudProviderCode() {
		return CLOUD_PROVIDER_CODE
	}

	/**
	 * Provides a Collection of NetworkTypes that can be managed by this provider
	 * @return Collection of NetworkType
	 */
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
		log.info("Initializeing network provider for ${cloud.name}")
		ServiceResponse rtn = ServiceResponse.prepare()
		try {
			NetworkServer networkServer = new NetworkServer(
				name: cloud.name,
				type: new NetworkServerType(code:"proxmox-ve.network")
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
			// cleanup is done by type, so we do not need to load the record
			// NetworkServer networkServer = morpheusContext.services.networkServer.find(new DataQuery().withFilters([new DataFilter('type.code', "amazon"), new DataFilter('zoneId', cloud.id)]))
			// NetworkServer networkServer = cloud.networkServer // this works too, ha
			NetworkServer networkServer = new NetworkServer(
				name: cloud.name,
				type: new NetworkServerType(code:"proxmox-ve.network")
			)
			morpheus.integration.deleteCloudIntegration(cloud.id, networkServer).blockingGet()
			rtn.success = true
		} catch (Exception e) {
			rtn.success = false
			log.error("deleteProvider error: {}", e, e)
		}

		return rtn
	}

	/**
	 * Creates the Network submitted
	 * @param network Network information
	 * @param opts additional configuration options
	 * @return ServiceResponse
	 */
        @Override
        ServiceResponse createNetwork(Network network, Map opts) {
                try {
                        def apiClient = new ProxmoxApiClient(morpheus, network.cloud, plugin)
                        def targetNode = opts?.targetNode ?: 'pve'
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

	/**
	 * Updates the Network submitted
	 * @param network Network information
	 * @param opts additional configuration options
	 * @return ServiceResponse
	 */
        @Override
        ServiceResponse<Network> updateNetwork(Network network, Map opts) {
                try {
                        def apiClient = new ProxmoxApiClient(morpheus, network.cloud, plugin)

                        def networkConfig = [
                                iface    : network.name,
                                type     : 'bridge',
                                autostart: 1
                        ]

                        def targetNode = opts?.targetNode ?: network.configMap?.targetNode ?: 'pve'
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

	/**
	 * Deletes the Network submitted
	 * @param network Network information
	 * @return ServiceResponse
	 */
        @Override
        ServiceResponse deleteNetwork(Network network, Map opts) {
                try {
                        def apiClient = new ProxmoxApiClient(morpheus, network.cloud, plugin)
                        def targetNode = network.configMap?.targetNode ?: 'pve'
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
                        log.info("Creating subnet ${subnet.name} in network ${network.name}")

                        def apiClient = new ProxmoxApiClient(morpheus, network.cloud, plugin)
                        def targetNode = network.configMap?.targetNode ?: 'pve'

                        if (subnet.type?.code == 'proxmox-ve-vlan-subnet') {
                                def vlanId = subnet.vlanId ?: opts.vlanId
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
                        def apiClient = new ProxmoxApiClient(morpheus, network.cloud, plugin)
                        def targetNode = subnet.configMap?.targetNode ?: 'pve'

                        def config = [
                                address: subnet.cidr ? extractIpFromCidr(subnet.cidr) : null,
                                netmask: subnet.cidr ? extractNetmaskFromCidr(subnet.cidr) : null,
                                gateway: subnet.gateway
                        ]

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
		log.info("NVR: DELETE SUBNET")
		return ServiceResponse.success()
	}
	
	@Override
	Collection getRouterTypes() {
		return []
	}



	@Override
	ServiceResponse<Network> prepareNetwork(Network network, Map opts) {
		log.info("NVR: PREPARE NETWORK")
		return ServiceResponse.success(network);
	}


	@Override
        ServiceResponse validateNetwork(Network network, Map opts) {
                log.info("NVR: VALIDATE NETWORK")
                return ServiceResponse.success();
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
                int prefix = parts[1].toInteger()
                long mask = (0xffffffff << (32 - prefix)) & 0xffffffff
                return [
                        (mask >>> 24) & 0xff,
                        (mask >>> 16) & 0xff,
                        (mask >>> 8) & 0xff,
                        mask & 0xff
                ].join('.')
        }


}
