package com.morpheusdata.proxmox.ve

import com.morpheusdata.PrepareHostResponse
import com.morpheusdata.core.AbstractProvisionProvider
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.core.data.DataFilter
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.core.providers.VmProvisionProvider
import com.morpheusdata.core.util.HttpApiClient
import com.morpheusdata.core.providers.WorkloadProvisionProvider
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.Datastore
import com.morpheusdata.model.HostType
import com.morpheusdata.model.Icon
import com.morpheusdata.model.Instance
import com.morpheusdata.model.LogLevel
import com.morpheusdata.model.OptionType
import com.morpheusdata.model.PlatformType
import com.morpheusdata.model.ServicePlan
import com.morpheusdata.model.StorageVolumeType
import com.morpheusdata.model.VirtualImage
import com.morpheusdata.model.VirtualImageLocation
import com.morpheusdata.model.Workload
import com.morpheusdata.model.projection.ComputeServerIdentityProjection
import com.morpheusdata.model.projection.DatastoreIdentity
import com.morpheusdata.model.provisioning.HostRequest
import com.morpheusdata.model.provisioning.WorkloadRequest
import com.morpheusdata.model.Cloud
import com.morpheusdata.request.ResizeRequest
import com.morpheusdata.response.PrepareWorkloadResponse
import com.morpheusdata.response.ProvisionResponse
import com.morpheusdata.response.ServiceResponse
import groovy.util.logging.Slf4j

@Slf4j
class ProxmoxVeProvisionProvider extends AbstractProvisionProvider implements VmProvisionProvider, WorkloadProvisionProvider, WorkloadProvisionProvider.ResizeFacet {
        public static final String PROVISION_PROVIDER_CODE = 'proxmox-ve-provision'

	protected MorpheusContext context
	protected ProxmoxVePlugin plugin

	public ProxmoxVeProvisionProvider(ProxmoxVePlugin plugin, MorpheusContext ctx) {
		super()
		this.@context = ctx
		this.@plugin = plugin
	}

	@Override
	Boolean canAddVolumes() {
		return true
	}

	@Override
	Boolean hasNetworks() {
		return true
	}

	@Override
	Boolean supportsAgent() {
		return true
	}

	@Override
	Boolean canCustomizeRootVolume() {
		return true
	}

	@Override
	Boolean canCustomizeDataVolumes() {
		return true
	}

	Boolean createDefaultInstanceType() {
		return false
	}

	@Override
	ServiceResponse<PrepareWorkloadResponse> prepareWorkload(Workload workload, WorkloadRequest workloadRequest, Map opts) {
		ServiceResponse<PrepareWorkloadResponse> resp = new ServiceResponse<PrepareWorkloadResponse>(
			true,
			'',
			null,
			new PrepareWorkloadResponse(workload:workload)
		)
		return resp
	}

	@Override
	String getProvisionTypeCode() {
		return PROVISION_PROVIDER_CODE
	}

       @Override
       Icon getCircularIcon() {
               return new Icon(path:'proxmox-logo-stacked-color.svg', darkPath:'proxmox-logo-stacked-inverted-color.svg')
       }

	@Override
	Collection<OptionType> getOptionTypes() {
		def options = []

		options << new OptionType(
				name: 'skip agent install',
				code: 'provisionType.proxmox.noAgent',
				category: 'provisionType.proxmox',
				inputType: OptionType.InputType.CHECKBOX,
				fieldName: 'noAgent',
				fieldContext: 'config',
				fieldCode: 'gomorpheus.optiontype.SkipAgentInstall',
				fieldLabel: 'Skip Agent Install',
				fieldGroup:'Advanced Options',
				displayOrder: 4,
				required: false,
				enabled: true,
				editable: true,
				global:false,
				placeHolder:null,
				helpBlock:'Skipping Agent installation will result in a lack of logging and guest operating system statistics. Automation scripts may also be adversely affected.',
				defaultValue: false,
				custom:false,
				fieldClass:null
		)

		return options
	}

	@Override
	Collection<OptionType> getNodeOptionTypes() {
		Collection<OptionType> nodeOptions = []

		nodeOptions << new OptionType(
				name: 'virtual image',
				category:'provisionType.proxmox.custom',
				code: 'proxmox-node-image',
				fieldContext: 'containerType',
				fieldName: 'virtualImage.id',
				fieldCode: 'gomorpheus.label.vmImage',
				fieldLabel: 'VM Image',
				fieldGroup: null,
				inputType: OptionType.InputType.SELECT,
				displayOrder:10,
				fieldClass:null,
				required: false,
				editable: false,
				noSelection: 'Select',
				optionSourceType: "proxmox",
				optionSource: 'proxmoxVirtualImages'
		)

		return nodeOptions
	}

	@Override
	Collection<StorageVolumeType> getRootVolumeStorageTypes() {
		return this.getStorageVolumeTypes()
	}

	@Override
	Collection<StorageVolumeType> getDataVolumeStorageTypes() {
		return this.getStorageVolumeTypes()
	}

	Collection<StorageVolumeType> getStorageVolumeTypes() {
		Collection<StorageVolumeType> volumeTypes = []

		volumeTypes << new StorageVolumeType(
				name: "Proxmox VM Generic Volume Type",
				code: "proxmox.vm.generic.volume.type",
				externalId: "proxmox.vm.generic.volume.type",
				displayOrder: 0,
				editable: true,
				resizable: true
		)

		return volumeTypes
	} 

	@Override
	Collection<ServicePlan> getServicePlans() {
		Collection<ServicePlan> plans = []

		plans << new ServicePlan([code:'proxmox-ve-vm-1024', name:'1 vCPU, 1GB Memory', description:'1 vCPU, 1GB Memory', sortOrder:1,
										 maxStorage: 10l * 1024l * 1024l * 1024l, maxMemory: 1l * 1024l * 1024l * 1024l, maxCores:1,
										 customMaxStorage:true, customMaxDataStorage:true, addVolumes:true])

		plans << new ServicePlan([code:'proxmox-ve-vm-2048', name:'1 vCPU, 2GB Memory', description:'1 vCPU, 2GB Memory', sortOrder:2,
								  maxStorage: 20l * 1024l * 1024l * 1024l, maxMemory: 2l * 1024l * 1024l * 1024l, maxCores:1,
								  customMaxStorage:true, customMaxDataStorage:true, addVolumes:true])

		plans << new ServicePlan([code:'proxmox-ve-vm-2048-2', name:'2 vCPU, 2GB Memory', description:'2 vCPU, 2GB Memory', sortOrder:3,
								  maxStorage: 20l * 1024l * 1024l * 1024l, maxMemory: 2l * 1024l * 1024l * 1024l, maxCores:2,
								  customMaxStorage:true, customMaxDataStorage:true, addVolumes:true])

		plans << new ServicePlan([code:'proxmox-ve-vm-4096', name:'1 vCPU, 4GB Memory', description:'1 vCPU, 4GB Memory', sortOrder:4,
								  maxStorage: 40l * 1024l * 1024l * 1024l, maxMemory: 4l * 1024l * 1024l * 1024l, maxCores:1,
								  customMaxStorage:true, customMaxDataStorage:true, addVolumes:true])

		plans << new ServicePlan([code:'proxmox-ve-vm-4096-2', name:'2 vCPU, 4GB Memory', description:'2 vCPU, 4GB Memory', sortOrder:5,
								  maxStorage: 40l * 1024l * 1024l * 1024l, maxMemory: 4l * 1024l * 1024l * 1024l, maxCores:2,
								  customMaxStorage:true, customMaxDataStorage:true, addVolumes:true])

		plans << new ServicePlan([code:'proxmox-ve-vm-8192', name:'2 vCPU, 8GB Memory', description:'2 vCPU, 8GB Memory', sortOrder:6,
								  maxStorage: 80l * 1024l * 1024l * 1024l, maxMemory: 8l * 1024l * 1024l * 1024l, maxCores:2,
								  customMaxStorage:true, customMaxDataStorage:true, addVolumes:true])

		plans << new ServicePlan([code:'proxmox-ve-vm-16384', name:'2 vCPU, 16GB Memory', description:'2 vCPU, 16GB Memory', sortOrder:7,
										 maxStorage: 160l * 1024l * 1024l * 1024l, maxMemory: 16l * 1024l * 1024l * 1024l, maxCores:2,
										 customMaxStorage:true, customMaxDataStorage:true, addVolumes:true])

		plans << new ServicePlan([code:'proxmox-ve-vm-24576', name:'4 vCPU, 24GB Memory', description:'4 vCPU, 24GB Memory', sortOrder:8,
										 maxStorage: 240l * 1024l * 1024l * 1024l, maxMemory: 24l * 1024l * 1024l * 1024l, maxCores:4,
										 customMaxStorage:true, customMaxDataStorage:true, addVolumes:true])

		plans << new ServicePlan([code:'proxmox-ve-vm-32768', name:'4 vCPU, 32GB Memory', description:'4 vCPU, 32GB Memory', sortOrder:9,
										 maxStorage: 320l * 1024l * 1024l * 1024l, maxMemory: 32l * 1024l * 1024l * 1024l, maxCores:4,
										 customMaxStorage:true, customMaxDataStorage:true, addVolumes:true])

		plans << new ServicePlan([code:'proxmox-ve-internal-custom', editable:false, name:'Proxmox Custom', description:'Proxmox Custom', sortOrder:0,
										 customMaxStorage:true, customMaxDataStorage:true, addVolumes:true, customCpu: true, customCores: true, customMaxMemory: true, deletable: false, provisionable: false,
										 maxStorage:0l, maxMemory: 0l,  maxCpu:0])
		return plans
	}

	@Override
	ServiceResponse validateWorkload(Map opts) {
		def errors = [:]
		
		if (!opts.cloud?.id) {
			errors.cloud = 'Cloud is required'
		}
		
		if (!opts.server?.plan?.maxMemory || opts.server.plan.maxMemory < 512 * 1024 * 1024) {
			errors.memory = 'Minimum 512MB memory required'
		}
		
		if (!opts.server?.plan?.maxCores || opts.server.plan.maxCores < 1) {
			errors.cores = 'Minimum 1 CPU core required'
		}
		
		if (errors) {
			return ServiceResponse.error('Validation failed', null, [errors: errors])
		}
		
		return ServiceResponse.success()
	}

	@Override
        ServiceResponse<ProvisionResponse> runWorkload(Workload workload, WorkloadRequest workloadRequest, Map opts) {
               log.info("Starting VM provisioning via Proxmox API")

               try {
                       if (!workloadRequest?.cloud) {
                               return ServiceResponse.error("Cloud configuration is required")
                       }

                       def apiClient = new ProxmoxApiClient(context, workloadRequest.cloud, plugin)

                       def connectionTest = apiClient.testConnection()
                       if (!connectionTest.success) {
                               return ServiceResponse.error("Cannot connect to Proxmox: ${connectionTest.error}")
                       }

                       def vmConfig = buildVmConfiguration(workloadRequest, opts)
                       def targetNode = selectTargetNode(workloadRequest, apiClient)

                       log.info("Creating VM on node ${targetNode} with config: ${vmConfig}")
                       def createResult = apiClient.createVm(targetNode, vmConfig)
                       def vmId = createResult?.vmid ?: vmConfig.vmid

                       if (!vmId) {
                               return ServiceResponse.error("Failed to obtain VM ID after creation")
                       }

                       configureVmResources(apiClient, targetNode, vmId, workloadRequest)
                       configureVmNetwork(apiClient, targetNode, vmId, workloadRequest)
                       configureVmStorage(apiClient, targetNode, vmId, workloadRequest)
                       configureCloudInitViaApi(apiClient, targetNode, vmId, workloadRequest)

                       log.info("Starting VM ${vmId}")
                       def startResult = apiClient.startVm(targetNode, vmId)
                       if (!startResult) {
                               log.warn("Failed to start VM, but continuing...")
                       }

                       def finalStatus = monitorVmStartup(apiClient, targetNode, vmId)
                       updateWorkloadDetails(workload, workloadRequest, targetNode, vmId, finalStatus)

                       return ServiceResponse.success(new ProvisionResponse(success: true, message: "VM provisioned successfully", externalId: vmId))

               } catch (Exception e) {
                       log.error("VM provisioning failed: ${e.message}", e)
                       return ServiceResponse.error("Provisioning failed: ${e.message}")
               }
        }

       private def buildVmConfiguration(WorkloadRequest workloadRequest, Map opts) {
               def nextId = getNextAvailableVmId(workloadRequest.cloud)
               def vmConfig = [:]
               
               vmConfig.vmid = nextId
               vmConfig.name = sanitizeVmName(workloadRequest.server?.name ?: "morpheus-vm-${nextId}")
               vmConfig.memory = Math.max(512, (workloadRequest.maxMemory ?: 2048L * 1024L * 1024L) / (1024L * 1024L))
               vmConfig.cores = Math.max(1, workloadRequest.maxCores ?: 2)
               vmConfig.sockets = 1
               vmConfig.ostype = determineOsType(workloadRequest)
               vmConfig.boot = 'order=scsi0;ide2;net0'
               vmConfig.agent = opts.noAgent ? 0 : 1
               vmConfig.onboot = 1
               vmConfig.protection = 0
               
               return vmConfig
       }

       private def selectTargetNode(WorkloadRequest workloadRequest, apiClient) {
               def targetNode = workloadRequest.server?.getConfigProperty('proxmoxNode')
               if (targetNode) {
                       return targetNode
               }
               
               try {
                       def nodes = apiClient.getClusterNodes()
                       if (nodes && nodes.size() > 0) {
                               def availableNodes = nodes.findAll { it.status == 'online' }
                               if (availableNodes) {
                                       return availableNodes.first().node
                               }
                               return nodes.first().node
                       }
               } catch (Exception e) {
                       log.warn("Could not get cluster nodes: ${e.message}")
               }
               
               return 'pve'
       }

       private def configureVmResources(apiClient, targetNode, vmId, workloadRequest) {
               try {
                       def resources = [:]
                       if (workloadRequest.maxMemory) {
                               resources.memory = Math.max(512, workloadRequest.maxMemory / 1024 / 1024)
                       }
                       if (workloadRequest.maxCores) {
                               resources.cores = Math.max(1, workloadRequest.maxCores)
                       }
                       if (resources) {
                               apiClient.resizeVmResources(targetNode, vmId, resources)
                       }
               } catch (Exception e) {
                       log.warn("Failed to configure VM resources: ${e.message}")
               }
       }

       private def configureVmNetwork(apiClient, targetNode, vmId, workloadRequest) {
               try {
                       def networkBridge = workloadRequest.server?.getConfigProperty('networkBridge') ?: 'vmbr0'
                       def networkModel = workloadRequest.server?.getConfigProperty('networkModel') ?: 'virtio'
                       apiClient.configureVmNetwork(targetNode, vmId, networkBridge, networkModel)
               } catch (Exception e) {
                       log.warn("Failed to configure VM network: ${e.message}")
               }
       }

       private def configureVmStorage(apiClient, targetNode, vmId, workloadRequest) {
               try {
                       def storage = workloadRequest.server?.getConfigProperty('storage') ?: 'local-lvm'
                       def diskSize = calculateDiskSize(workloadRequest)
                       apiClient.configureVmDisk(targetNode, vmId, storage, diskSize)
               } catch (Exception e) {
                       log.warn("Failed to configure VM storage: ${e.message}")
               }
       }

       private def configureCloudInitViaApi(apiClient, targetNode, vmId, workloadRequest) {
               try {
                       log.info("Configuring cloud-init via Proxmox API")
                       def cloudInitConfig = [:]
                       
                       cloudInitConfig.user = workloadRequest.server?.sshUsername ?: 'morpheus'
                       
                       if (workloadRequest.server?.sshKey) {
                               cloudInitConfig.sshKeys = [workloadRequest.server.sshKey]
                       }
                       
                       if (workloadRequest.server?.sshPassword) {
                               cloudInitConfig.password = workloadRequest.server.sshPassword
                       }
                       
                       if (workloadRequest.networkConfiguration) {
                               cloudInitConfig.ipConfig = buildIpConfig(workloadRequest.networkConfiguration)
                       }
                       
                       if (cloudInitConfig) {
                               apiClient.configureCloudInit(targetNode, vmId, cloudInitConfig)
                       }
               } catch (Exception e) {
                       log.warn("Failed to configure cloud-init: ${e.message}")
               }
       }

       private def buildIpConfig(networkConfig) {
               if (networkConfig?.staticIp && networkConfig?.ipAddress) {
                       def config = "ip=${networkConfig.ipAddress}"
                       if (networkConfig.subnetMask) {
                               config += "/${networkConfig.subnetMask}"
                       }
                       if (networkConfig.gateway) {
                               config += ",gw=${networkConfig.gateway}"
                       }
                       return config
               }
               return "ip=dhcp"
       }

       private def monitorVmStartup(apiClient, targetNode, vmId) {
               def maxAttempts = 30
               def attempt = 0
               
               while (attempt < maxAttempts) {
                       try {
                               def status = apiClient.getVmStatus(targetNode, vmId)
                               if (status?.data?.status == 'running') {
                                       log.info("VM ${vmId} is running")
                                       return status
                               }
                               Thread.sleep(5000)
                               attempt++
                       } catch (Exception e) {
                               log.warn("Error checking VM status: ${e.message}")
                               attempt++
                       }
               }
               
               log.warn("VM ${vmId} did not start within timeout period")
               return null
       }

       private def updateWorkloadDetails(Workload workload, WorkloadRequest workloadRequest, targetNode, vmId, status) {
               if (workload?.server) {
                       workload.server.externalId = vmId.toString()
                       workload.server.uniqueId = vmId.toString()
                       workload.server.powerState = 'on'
                       workload.server.hostname = workload.server.name
               }

               if (workloadRequest?.server) {
                       workloadRequest.server.externalId = vmId.toString()
                       workloadRequest.server.uniqueId = vmId.toString()
                       workloadRequest.server.powerState = 'on'
                       workloadRequest.server.hostname = workloadRequest.server.name
               }
       }

       private def sanitizeVmName(String name) {
               return name?.replaceAll(/[^a-zA-Z0-9\-_]/, '-')?.toLowerCase()
       }

       private def determineOsType(WorkloadRequest workloadRequest) {
               def osType = workloadRequest.server?.osType?.toLowerCase()
               if (osType?.contains('windows')) {
                       return 'win10'
               }
               return 'l26'
       }

       private def calculateDiskSize(WorkloadRequest workloadRequest) {
               def maxStorage = workloadRequest.maxStorage ?: (32L * 1024L * 1024L * 1024L)
               def sizeInGB = Math.max(8, maxStorage / (1024L * 1024L * 1024L))
               return "${sizeInGB}G"
       }

       private def getNextAvailableVmId(Cloud cloud) {
               try {
                       def apiClient = new ProxmoxApiClient(context, cloud, plugin)
                       def result = apiClient.getNextVmId('pve')
                       return result?.data ?: (100 + (System.currentTimeMillis() % 900))
               } catch (Exception e) {
                       log.warn("Failed to get next VM ID: ${e.message}")
                       return 100 + (System.currentTimeMillis() % 900)
               }
       }

	private DatastoreIdentity getDefaultDatastore(Long cloudId) {
		log.debug("getDefaultDatastoreName()...")
		Datastore rtn = null
		context.async.cloud.datastore.list(new DataQuery().withFilters([
				new DataFilter("refType", "ComputeZone"),
				new DataFilter("refId", cloudId)
		])).blockingForEach { ds ->
			if (rtn == null) {
				rtn = ds
			} else if (ds.name.contains("zfs") || (ds.getFreeSpace() > rtn.getFreeSpace())) {
				rtn = ds
			}
		}
		return rtn
	}

	private ComputeServer getHypervisorHostByExternalId(Long cloudId, String externalId) {
		log.info("Fetch Hypervisor Host by Cloud/External Id: $cloudId/$externalId")

		ComputeServer hvNode
		def hostIdentityProjection = context.async.computeServer.listIdentityProjections(cloudId, null).filter {
			ComputeServerIdentityProjection projection ->
				if (projection.externalId == externalId) {
					return true
				}
				false
		}.subscribe {
			log.info("Found Host IdentityProjection: $it.id")
			List<Long> idList = [it.id]
			hvNode = context.async.computeServer.listById(idList).blockingFirst()
			log.debug("Returning hvHost: $hvNode.sshHost")
		}

		return hvNode
	}

	@Override
	ServiceResponse finalizeWorkload(Workload workload) {
		return ServiceResponse.success()
	}

	@Override
	ServiceResponse stopWorkload(Workload workload) {
		try {
			if (!workload?.server?.cloud) {
				return ServiceResponse.error("Invalid workload configuration")
			}
			
			ComputeServer computeServer = workload.server
			def apiClient = new ProxmoxApiClient(context, computeServer.cloud, plugin)
			def node = computeServer.parentServer?.externalId ?: 'pve'
			def vmId = computeServer.externalId

			if (!vmId) {
				return ServiceResponse.error("VM ID not found")
			}

			def result = apiClient.stopVm(node, vmId)
			if (result) {
				return ServiceResponse.success("VM stopped successfully")
			} else {
				return ServiceResponse.error("Failed to stop VM")
			}
		} catch (Exception e) {
			log.error("Error performing stop on VM: ${e}", e)
			return ServiceResponse.error("Error performing stop on VM: ${e}")
		}
	}

	@Override
	ServiceResponse startWorkload(Workload workload) {
		try {
			if (!workload?.server?.cloud) {
				return ServiceResponse.error("Invalid workload configuration")
			}
			
			ComputeServer computeServer = workload.server
			def apiClient = new ProxmoxApiClient(context, computeServer.cloud, plugin)
			def node = computeServer.parentServer?.externalId ?: 'pve'
			def vmId = computeServer.externalId

			if (!vmId) {
				return ServiceResponse.error("VM ID not found")
			}

			def result = apiClient.startVm(node, vmId)
			if (result) {
				return ServiceResponse.success("VM started successfully")
			} else {
				return ServiceResponse.error("Failed to start VM")
			}
		} catch (Exception e) {
			log.error("Error performing start on VM: ${e}", e)
			return ServiceResponse.error("Error performing start on VM: ${e}")
		}
	}

	@Override
	ServiceResponse restartWorkload(Workload workload) {
		try {
			if (!workload?.server?.cloud) {
				return ServiceResponse.error("Invalid workload configuration")
			}
			
			ComputeServer computeServer = workload.server
			def apiClient = new ProxmoxApiClient(context, computeServer.cloud, plugin)
			def node = computeServer.parentServer?.externalId ?: 'pve'
			def vmId = computeServer.externalId

			if (!vmId) {
				return ServiceResponse.error("VM ID not found")
			}

			def result = apiClient.restartVm(node, vmId)
			if (result) {
				return ServiceResponse.success("VM restarted successfully")
			} else {
				return ServiceResponse.error("Failed to restart VM")
			}
		} catch (Exception e) {
			log.error("Error performing restart on VM: ${e}", e)
			return ServiceResponse.error("Error performing restart on VM: ${e}")
		}
	}

	@Override
	ServiceResponse removeWorkload(Workload workload, Map opts) {
		try {
			if (!workload?.server?.cloud) {
				return ServiceResponse.error("Invalid workload configuration")
			}
			
			ComputeServer server = workload.server
			def apiClient = new ProxmoxApiClient(context, server.cloud, plugin)
			def node = server.parentServer?.externalId ?: 'pve'
			def vmId = server.externalId

			if (!vmId) {
				return ServiceResponse.error("VM ID not found")
			}

			try {
				apiClient.stopVm(node, vmId)
				Thread.sleep(5000)
			} catch (Exception stopEx) {
				log.warn("Failed to stop VM before deletion: ${stopEx.message}")
			}

			def result = apiClient.deleteVm(node, vmId)
			if (result) {
				return ServiceResponse.success("VM removed successfully")
			} else {
				return ServiceResponse.error("Failed to remove VM")
			}
		} catch (Exception e) {
			log.error("Error performing destroy on VM: ${e}", e)
			return ServiceResponse.error("Error performing destroy on VM: ${e}")
		}
	}

	@Override
	ServiceResponse<ProvisionResponse> getServerDetails(ComputeServer server) {
		try {
			if (!server?.cloud || !server?.externalId) {
				return new ServiceResponse<ProvisionResponse>(false, "Invalid server configuration", null, null)
			}
			
			def apiClient = new ProxmoxApiClient(context, server.cloud, plugin)
			def node = server.parentServer?.externalId ?: 'pve'
			def vmId = server.externalId
			
			def status = apiClient.getVmStatus(node, vmId)
			def config = apiClient.getVmConfig(node, vmId)
			
			def provisionResponse = new ProvisionResponse(success: true)
			
			if (status?.data?.status) {
				provisionResponse.powerState = status.data.status == 'running' ? 'on' : 'off'
			}
			
			return new ServiceResponse<ProvisionResponse>(true, null, null, provisionResponse)
		} catch (Exception e) {
			log.error("Error getting server details: ${e.message}", e)
			return new ServiceResponse<ProvisionResponse>(true, null, null, new ProvisionResponse(success: true))
		}
	}

	@Override
	ServiceResponse createWorkloadResources(Workload workload, Map opts) {
		return ServiceResponse.success()
	}

	@Override
	ServiceResponse stopServer(ComputeServer computeServer) {
		try {
			if (!computeServer?.cloud) {
				return ServiceResponse.error("Invalid server configuration")
			}
			
			def apiClient = new ProxmoxApiClient(context, computeServer.cloud, plugin)
			def node = computeServer.parentServer?.externalId ?: 'pve'
			def vmId = computeServer.externalId

			if (!vmId) {
				return ServiceResponse.error("VM ID not found")
			}

			def result = apiClient.stopVm(node, vmId)
			if (result) {
				return ServiceResponse.success("Server stopped successfully")
			} else {
				return ServiceResponse.error("Failed to stop server")
			}
		} catch (Exception e) {
			log.error("Error performing stop on server: ${e}", e)
			return ServiceResponse.error("Error performing stop on server: ${e}")
		}
	}

	@Override
	ServiceResponse startServer(ComputeServer computeServer) {
		try {
			if (!computeServer?.cloud) {
				return ServiceResponse.error("Invalid server configuration")
			}
			
			def apiClient = new ProxmoxApiClient(context, computeServer.cloud, plugin)
			def node = computeServer.parentServer?.externalId ?: 'pve'
			def vmId = computeServer.externalId

			if (!vmId) {
				return ServiceResponse.error("VM ID not found")
			}

			def result = apiClient.startVm(node, vmId)
			if (result) {
				return ServiceResponse.success("Server started successfully")
			} else {
				return ServiceResponse.error("Failed to start server")
			}
		} catch (Exception e) {
			log.error("Error performing start on server: ${e}", e)
			return ServiceResponse.error("Error performing start on server: ${e}")
		}
	}

	@Override
	MorpheusContext getMorpheus() {
		return this.@context
	}

	@Override
	Plugin getPlugin() {
		return this.@plugin
	}

        @Override
        String getCode() {
                return 'proxmox-ve-provision'
        }

	@Override
	String getName() {
		return 'Proxmox VE Provisioning'
	}

	protected ComputeServer saveAndGet(ComputeServer server) {
		def saveSuccessful = context.async.computeServer.bulkSave([server]).blockingGet()
		if(!saveSuccessful) {
			log.warn("Error saving server: ${server?.id}" )
		}
		return context.async.computeServer.get(server.id).blockingGet()
	}

	@Override
	HostType getHostType() {
		HostType.vm
	}

	@Override
	ServiceResponse resizeWorkload(Instance instance, Workload workload, ResizeRequest resizeRequest, Map opts) {
		log.debug("resizeWorkload ${workload ? "workload" : "server"}.id: ${workload?.id} - opts: ${opts}")

		boolean isWorkload = true
		def server = workload.getServer()
		ServiceResponse rtn = ServiceResponse.success()

		ComputeServer computeServer = context.async.computeServer.get(server.id).blockingGet()
		
		try {
			def apiClient = new ProxmoxApiClient(context, computeServer.cloud, plugin)
			def node = computeServer.parentServer?.externalId ?: 'pve'
			def vmId = computeServer.externalId

			if (!vmId) {
				return ServiceResponse.error("VM ID not found")
			}

			computeServer.status = 'resizing'
			computeServer = saveAndGet(computeServer)

			def requestedMemory = resizeRequest.maxMemory
			def requestedCores = resizeRequest?.maxCores

			def currentMemory = isWorkload ? 
				(workload.maxMemory ?: workload.getConfigProperty('maxMemory')?.toLong()) :
				(computeServer.maxMemory ?: computeServer.getConfigProperty('maxMemory')?.toLong())
			def currentCores = isWorkload ? 
				(workload.maxCores ?: 1) :
				(server.maxCores ?: 1)

			def neededMemory = requestedMemory - currentMemory
			def neededCores = (requestedCores ?: 1) - (currentCores ?: 1)

			if (neededMemory > 100000000l || neededMemory < -100000000l || neededCores != 0) {
				log.info("Resizing VM ${vmId} on node ${node}")
				log.info("Resizing vm: ${workload.getInstance().name} with ${requestedCores} cores and ${requestedMemory} memory")
				
				def resources = [
					memory: Math.max(512, requestedMemory / 1024 / 1024), 
					cores: Math.max(1, requestedCores)
				]
				def resizeResult = apiClient.resizeVmResources(node, vmId, resources)
				
				if (resizeResult) {
					apiClient.restartVm(node, vmId)
					computeServer.status = 'provisioned'
					computeServer = saveAndGet(computeServer)
				} else {
					throw new RuntimeException("Failed to resize VM resources")
				}
			}
		} catch (Exception e) {
			log.error("Unable to resize workload: ${e.message}", e)
			computeServer.status = 'provisioned'
			if (!isWorkload)
				computeServer.statusMessage = "Unable to resize server: ${e.message}"
			computeServer = saveAndGet(computeServer)
			rtn.success = false
			rtn.setError("Unable to resize workload: ${e.message}")
		}
		return rtn
	}

        @Override
        ServiceResponse validateHost(ComputeServer server, Map opts) {
                try {
                        if (!server?.cloud) {
                                return ServiceResponse.error("Invalid server configuration")
                        }
                        
                        def apiClient = new ProxmoxApiClient(context, server.cloud, plugin)
                        def connectionTest = apiClient.testConnection()

                        if (connectionTest.success) {
                                return ServiceResponse.success()
                        } else {
                                return ServiceResponse.error("Cannot connect to Proxmox host: ${connectionTest.error}")
                        }
                } catch (Exception e) {
                        return ServiceResponse.error("Host validation failed: ${e.message}")
                }
        }

        @Override
        ServiceResponse<PrepareHostResponse> prepareHost(ComputeServer server, HostRequest hostRequest, Map opts) {
                log.info("Preparing Proxmox host ${server.name} for deployment")
                return ServiceResponse.success()
        }

        @Override
        ServiceResponse<ProvisionResponse> runHost(ComputeServer server, HostRequest hostRequest, Map opts) {
                log.info("Running host operations for ${server.name}")
                return ServiceResponse.success()
        }

        @Override
        ServiceResponse finalizeHost(ComputeServer server) {
                log.info("Finalizing host setup for ${server.name}")
                return ServiceResponse.success()
        }
}