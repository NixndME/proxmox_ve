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
import com.morpheusdata.proxmox.ve.util.ProxmoxApiComputeUtil
import groovy.util.logging.Slf4j

@Slf4j
class ProxmoxVeProvisionProvider extends AbstractProvisionProvider implements VmProvisionProvider, WorkloadProvisionProvider, WorkloadProvisionProvider.ResizeFacet { //, ProvisionProvider.BlockDeviceNameFacet {
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
		return true;
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
		return false;
	}

	/**
	 * This method is called before runWorkload and provides an opportunity to perform action or obtain configuration
	 * that will be needed in runWorkload. At the end of this method, if deploying a ComputeServer with a VirtualImage,
	 * the sourceImage on ComputeServer should be determined and saved.
	 * @param workload the Workload object we intend to provision along with some of the associated data needed to determine
	 *                 how best to provision the workload
	 * @param workloadRequest the RunWorkloadRequest object containing the various configurations that may be needed
	 *                        in running the Workload. This will be passed along into runWorkload
	 * @param opts additional configuration options that may have been passed during provisioning
	 * @return Response from API
	 */
	@Override
	ServiceResponse<PrepareWorkloadResponse> prepareWorkload(Workload workload, WorkloadRequest workloadRequest, Map opts) {
		ServiceResponse<PrepareWorkloadResponse> resp = new ServiceResponse<PrepareWorkloadResponse>(
			true, // successful
			'', // no message
			null, // no errors
			new PrepareWorkloadResponse(workload:workload) // adding the workload to the response for convenience
		)
		return resp
	}

	/**
	 * Some older clouds have a provision type code that is the exact same as the cloud code. This allows one to set it
	 * to match and in doing so the provider will be fetched via the cloud providers {@link ProxmoxVeCloudProvider#getDefaultProvisionTypeCode()} method.
	 * @return code for overriding the ProvisionType record code property
	 */
	@Override
	String getProvisionTypeCode() {
		return PROVISION_PROVIDER_CODE
	}

	/**
	 * Provide an icon to be displayed for ServicePlans, VM detail page, etc.
	 * where a circular icon is displayed
	 * @since 0.13.6
	 * @return Icon
	 */
       @Override
       Icon getCircularIcon() {
               // icon filenames located under src/assets/images
               return new Icon(path:'proxmox-logo-stacked-color.svg', darkPath:'proxmox-logo-stacked-inverted-color.svg')
       }

	/**
	 * Provides a Collection of OptionType inputs that need to be made available to various provisioning Wizards
	 * @return Collection of OptionTypes
	 */
	@Override
	Collection<OptionType> getOptionTypes() {
		def options = []
/*
		options << new OptionType(
				name: 'skip agent install',
				code: 'provisionType.nutanixPrism.noAgent',
				category: 'provisionType.nutanixPrism',
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
*/

		return options
	}

	/**
	 * Provides a Collection of OptionType inputs for configuring node types
	 * @since 0.9.0
	 * @return Collection of OptionTypes
	 */
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

	/**
	 * Provides a Collection of StorageVolumeTypes that are available for root StorageVolumes
	 * @return Collection of StorageVolumeTypes
	 */
	@Override
	Collection<StorageVolumeType> getRootVolumeStorageTypes() {
		return this.getStorageVolumeTypes()
	}

	/**
	 * Provides a Collection of StorageVolumeTypes that are available for data StorageVolumes
	 * @return Collection of StorageVolumeTypes
	 */
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

	/**
	 * Provides a Collection of ${@link ServicePlan} related to this ProvisionProvider that can be seeded in.
	 * Some clouds do not use this as they may be synced in from the public cloud. This is more of a factor for
	 * On-Prem clouds that may wish to have some precanned plans provided for it.
	 * @return Collection of ServicePlan sizes that can be seeded in at plugin startup.
	 */
	@Override
	Collection<ServicePlan> getServicePlans() {
		Collection<ServicePlan> plans = []
		//plans << new ServicePlan([code:'proxmox-ve-vm-512', name:'1 vCPU, 512MB Memory', description:'1 vCPU, 512MB Memory', sortOrder:0,
		//								 maxStorage:10l * 1024l * 1024l * 1024l, maxMemory: 1l * 512l * 1024l * 1024l, maxCores:1,
		//								 customMaxStorage:true, customMaxDataStorage:true, addVolumes:true])

		plans << new ServicePlan([code:'proxmox-ve-vm-1024', name:'1 vCPU, 1GB Memory', description:'1 vCPU, 1GB Memory', sortOrder:1,
										 maxStorage: 10l * 1024l * 1024l * 1024l, maxMemory: 1l * 1024l * 1024l * 1024l, maxCores:1,
										 customMaxStorage:true, customMaxDataStorage:true, addVolumes:true])

		plans << new ServicePlan([code:'proxmox-ve-vm-2048', name:'1 vCPU, 2GB Memory', description:'1 vCPU, 2GB Memory', sortOrder:2,
								  maxStorage: 20l * 1024l * 1024l * 1024l, maxMemory: 2l * 1024l * 1024l * 1024l, maxCores:1,
								  customMaxStorage:true, customMaxDataStorage:true, addVolumes:true])

		plans << new ServicePlan([code:'proxmox-ve-vm-2048-2', name:'2 vCPU, 2GB Memory', description:'2 vCPU, 2GB Memory', sortOrder:2,
								  maxStorage: 20l * 1024l * 1024l * 1024l, maxMemory: 2l * 1024l * 1024l * 1024l, maxCores:2,
								  customMaxStorage:true, customMaxDataStorage:true, addVolumes:true])

		plans << new ServicePlan([code:'proxmox-ve-vm-4096', name:'1 vCPU, 4GB Memory', description:'1 vCPU, 4GB Memory', sortOrder:3,
								  maxStorage: 40l * 1024l * 1024l * 1024l, maxMemory: 4l * 1024l * 1024l * 1024l, maxCores:1,
								  customMaxStorage:true, customMaxDataStorage:true, addVolumes:true])

		plans << new ServicePlan([code:'proxmox-ve-vm-4096-24', name:'2 vCPU, 4GB Memory', description:'2 vCPU, 4GB Memory', sortOrder:3,
								  maxStorage: 40l * 1024l * 1024l * 1024l, maxMemory: 4l * 1024l * 1024l * 1024l, maxCores:2,
								  customMaxStorage:true, customMaxDataStorage:true, addVolumes:true])

		plans << new ServicePlan([code:'proxmox-ve-vm-8192', name:'2 vCPU, 8GB Memory', description:'2 vCPU, 8GB Memory', sortOrder:4,
								  maxStorage: 80l * 1024l * 1024l * 1024l, maxMemory: 8l * 1024l * 1024l * 1024l, maxCores:2,
								  customMaxStorage:true, customMaxDataStorage:true, addVolumes:true])

		plans << new ServicePlan([code:'proxmox-ve-vm-8192', name:'2 vCPU, 8GB Memory', description:'2 vCPU, 8GB Memory', sortOrder:4,
								  maxStorage: 80l * 1024l * 1024l * 1024l, maxMemory: 8l * 1024l * 1024l * 1024l, maxCores:2,
								  customMaxStorage:true, customMaxDataStorage:true, addVolumes:true])

		plans << new ServicePlan([code:'proxmox-ve-vm-16384', name:'2 vCPU, 16GB Memory', description:'2 vCPU, 16GB Memory', sortOrder:5,
										 maxStorage: 160l * 1024l * 1024l * 1024l, maxMemory: 16l * 1024l * 1024l * 1024l, maxCores:2,
										 customMaxStorage:true, customMaxDataStorage:true, addVolumes:true])

		plans << new ServicePlan([code:'proxmox-ve-vm-24576', name:'4 vCPU, 24GB Memory', description:'4 vCPU, 24GB Memory', sortOrder:6,
										 maxStorage: 240l * 1024l * 1024l * 1024l, maxMemory: 24l * 1024l * 1024l * 1024l, maxCores:4,
										 customMaxStorage:true, customMaxDataStorage:true, addVolumes:true])

		plans << new ServicePlan([code:'proxmox-ve-vm-32768', name:'4 vCPU, 32GB Memory', description:'4 vCPU, 32GB Memory', sortOrder:7,
										 maxStorage: 320l * 1024l * 1024l * 1024l, maxMemory: 32l * 1024l * 1024l * 1024l, maxCores:4,
										 customMaxStorage:true, customMaxDataStorage:true, addVolumes:true])

		plans << new ServicePlan([code:'proxmox-ve-internal-custom', editable:false, name:'Proxmox Custom', description:'Proxmox Custom', sortOrder:0,
										 customMaxStorage:true, customMaxDataStorage:true, addVolumes:true, customCpu: true, customCores: true, customMaxMemory: true, deletable: false, provisionable: false,
										 maxStorage:0l, maxMemory: 0l,  maxCpu:0])
		return plans
	}


	/**
	 * Validates the provided provisioning options of a workload. A return of success = false will halt the
	 * creation and display errors
	 * @param opts options
	 * @return Response from API. Errors should be returned in the errors Map with the key being the field name and the error
	 * message as the value.
	 */
	@Override
	ServiceResponse validateWorkload(Map opts) {
		return ServiceResponse.success()
	}

	/**
	 * This method is a key entry point in provisioning a workload. This could be a vm, a container, or something else.
	 * Information associated with the passed Workload object is used to kick off the workload provision request
	 * @param workload the Workload object we intend to provision along with some of the associated data needed to determine
	 *                 how best to provision the workload
	 * @param workloadRequest the RunWorkloadRequest object containing the various configurations that may be needed
	 *                        in running the Workload
	 * @param opts additional configuration options that may have been passed during provisioning
	 * @return Response from API
	 */
	@Override
        ServiceResponse<ProvisionResponse> runWorkload(Workload workload, WorkloadRequest workloadRequest, Map opts) {
               log.info("Starting VM provisioning via Proxmox API (SSH-free)")

               try {
                       // Get API client - no SSH credentials needed
                       def apiClient = new ProxmoxApiClient(context, workloadRequest.cloud, plugin)

                       // Test connectivity first
                       def connectionTest = apiClient.testConnection()
                       if (!connectionTest.success) {
                               return ServiceResponse.error("Cannot connect to Proxmox: ${connectionTest.error}")
                       }

                       // Build VM configuration
                       def vmConfig = buildVmConfiguration(workloadRequest)
                       def targetNode = selectTargetNode(workloadRequest)

                       // Create VM via API
                       log.info("Creating VM via Proxmox API")
                       def createResult = apiClient.createVm(targetNode, vmConfig)
                       def vmId = createResult.vmid ?: vmConfig.vmid

                       // Configure VM resources via API
                       configureVmResources(apiClient, targetNode, vmId, workloadRequest)

                       // Configure cloud-init via API (no SSH)
                       configureCloudInitViaApi(apiClient, targetNode, vmId, workloadRequest)

                       // Start VM
                       log.info("Starting VM ${vmId}")
                       apiClient.startVm(targetNode, vmId)

                       // Monitor VM startup
                       def finalStatus = monitorVmStartup(apiClient, targetNode, vmId)

                       // Update workload with VM details
                       updateWorkloadDetails(workload, workloadRequest, targetNode, vmId, finalStatus)

                       return ServiceResponse.success(new ProvisionResponse(success: true, message: "VM provisioned successfully"))

               } catch (Exception e) {
                       log.error("VM provisioning failed: ${e.message}", e)
                       return ServiceResponse.error("Provisioning failed: ${e.message}")
               }
        }

       private def buildVmConfiguration(WorkloadRequest workloadRequest) {
               def vmConfig = [:]
               vmConfig.vmid = workloadRequest.server?.id ?: (System.currentTimeMillis() % 100000) as String
               vmConfig.name = workloadRequest.server?.name ?: "morpheus-vm-${vmConfig.vmid}"
               vmConfig.memory = (workloadRequest.maxMemory ?: 2048) / (1024 * 1024)
               vmConfig.cores = workloadRequest.maxCores ?: 2
               vmConfig.sockets = 1
               vmConfig.ostype = workloadRequest.server?.osType == 'windows' ? 'win10' : 'l26'
               vmConfig.storage = workloadRequest.server?.getConfigProperty('storage') ?: 'local-lvm'
               return vmConfig
       }

       private def selectTargetNode(WorkloadRequest workloadRequest) {
               def targetNode = workloadRequest.server?.getConfigProperty('proxmoxNode')
               if (!targetNode) {
                       try {
                               def apiClient = new ProxmoxApiClient(context, workloadRequest.cloud, plugin)
                               def nodes = apiClient.getClusterNodes()
                               targetNode = nodes?.first()?.node
                       } catch (Exception e) {
                               log.warn("Could not get cluster nodes, using 'pve' as default")
                               targetNode = 'pve'
                       }
               }
               return targetNode
       }

       private def configureVmResources(apiClient, targetNode, vmId, workloadRequest) {
               def resources = [:]
               if (workloadRequest.maxMemory) {
                       resources.memory = workloadRequest.maxMemory / 1024 / 1024
               }
               if (workloadRequest.maxCores) {
                       resources.cores = workloadRequest.maxCores
               }
               if (resources) {
                       apiClient.resizeVmResources(targetNode, vmId, resources)
               }
       }

       private def configureCloudInitViaApi(apiClient, targetNode, vmId, workloadRequest) {
               log.info("Configuring cloud-init via Proxmox API (no SSH)")
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
       }

       private def buildIpConfig(networkConfig) {
               if (networkConfig.staticIp) {
                       return "ip=${networkConfig.ipAddress}/${networkConfig.subnetMask},gw=${networkConfig.gateway}"
               } else {
                       return "ip=dhcp"
               }
       }

       private def monitorVmStartup(apiClient, targetNode, vmId) {
               def maxAttempts = 30
               def attempt = 0
               while (attempt < maxAttempts) {
                       try {
                               def status = apiClient.getVmStatus(targetNode, vmId)
                               if (status.data?.status == 'running') {
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
               throw new RuntimeException("VM failed to start within timeout period")
       }

       private def updateWorkloadDetails(Workload workload, WorkloadRequest workloadRequest, targetNode, vmId, status) {
               workload.server.externalId = vmId
               workload.server.uniqueId = vmId
               workload.server.powerState = 'on'
               workload.server.hostname = workload.server.name

               workloadRequest.server.externalId = vmId
               workloadRequest.server.uniqueId = vmId
               workloadRequest.server.powerState = 'on'
               workloadRequest.server.hostname = workloadRequest.server.name
       }


	private DatastoreIdentity getDefaultDatastore(Long cloudId) {
		log.debug("getDefaultDatastoreName()...")
		//returns the largest non-local datastore
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





	/**
	 * This method is called after successful completion of runWorkload and provides an opportunity to perform some final
	 * actions during the provisioning process. For example, ejected CDs, cleanup actions, etc
	 * @param workload the Workload object that has been provisioned
	 * @return Response from the API
	 */
	@Override
	ServiceResponse finalizeWorkload(Workload workload) {


		return ServiceResponse.success()
	}

	/**
	 * Issues the remote calls necessary top stop a workload element from running.
	 * @param workload the Workload we want to shut down
	 * @return Response from API
	 */
	@Override
	ServiceResponse stopWorkload(Workload workload) {
		try {
			HttpApiClient client = new HttpApiClient()
			ComputeServer computeServer = workload.server
			Map authConfig = plugin.getAuthConfig(computeServer.cloud)

			return ProxmoxApiComputeUtil.stopVM(client, authConfig, computeServer.parentServer.name, computeServer.externalId)
		} catch (e) {
			log.error "Error performing stop on VM: ${e}", e
			return ServiceResponse.error("Error performing stop on VM: ${e}")
		}
	}

	/**
	 * Issues the remote calls necessary to start a workload element for running.
	 * @param workload the Workload we want to start up.
	 * @return Response from API
	 */
	@Override
	ServiceResponse startWorkload(Workload workload) {
		try {
			HttpApiClient client = new HttpApiClient()
			ComputeServer computeServer = workload.server
			Map authConfig = plugin.getAuthConfig(computeServer.cloud)

			return ProxmoxApiComputeUtil.startVM(client, authConfig, computeServer.parentServer.name, computeServer.externalId)
		} catch (e) {
			log.error "Error performing start on VM: ${e}", e
			return ServiceResponse.error("Error performing start on VM: ${e}")
		}
	}

	/**
	 * Issues the remote calls to restart a workload element. In some cases this is just a simple alias call to do a stop/start,
	 * however, in some cases cloud providers provide a direct restart call which may be preferred for speed.
	 * @param workload the Workload we want to restart.
	 * @return Response from API
	 */
	@Override
	ServiceResponse restartWorkload(Workload workload) {
		// Generally a call to stopWorkLoad() and then startWorkload()
		return ServiceResponse.success()
	}

	/**
	 * This is the key method called to destroy / remove a workload. This should make the remote calls necessary to remove any assets
	 * associated with the workload.
	 * @param workload to remove
	 * @param opts map of options
	 * @return Response from API
	 */
	@Override
	ServiceResponse removeWorkload(Workload workload, Map opts) {
		try {
			HttpApiClient deleteClient = new HttpApiClient()
			HttpApiClient stopClient = new HttpApiClient()
			ComputeServer server = workload.server
			Cloud cloud = server.cloud
			Map authConfig = plugin.getAuthConfig(cloud)

			ProxmoxApiComputeUtil.stopVM(stopClient, authConfig, server.parentServer.name, server.externalId)
			sleep(5000)
			return ProxmoxApiComputeUtil.destroyVM(deleteClient, authConfig, server.parentServer.name, server.externalId)
		} catch (e) {
			log.error "Error performing destroy on VM: ${e}", e
			return ServiceResponse.error("Error performing destroy on VM: ${e}")
		}
	}

	/**
	 * Method called after a successful call to runWorkload to obtain the details of the ComputeServer. Implementations
	 * should not return until the server is successfully created in the underlying cloud or the server fails to
	 * create.
	 * @param server to check status
	 * @return Response from API. The publicIp and privateIp set on the WorkloadResponse will be utilized to update the ComputeServer
	 */
	@Override
	ServiceResponse<ProvisionResponse> getServerDetails(ComputeServer server) {
		return new ServiceResponse<ProvisionResponse>(true, null, null, new ProvisionResponse(success:true))
	}

	/**
	 * Method called before runWorkload to allow implementers to create resources required before runWorkload is called
	 * @param workload that will be provisioned
	 * @param opts additional options
	 * @return Response from API
	 */
	@Override
	ServiceResponse createWorkloadResources(Workload workload, Map opts) {
		return ServiceResponse.success()
	}

	/**
	 * Stop the server
	 * @param computeServer to stop
	 * @return Response from API
	 */
	@Override
	ServiceResponse stopServer(ComputeServer computeServer) {
		try {
			HttpApiClient client = new HttpApiClient()
			Map authConfig = plugin.getAuthConfig(computeServer.cloud)

			return ProxmoxApiComputeUtil.stopVM(client, authConfig, computeServer.parentServer.name, computeServer.externalId)
		} catch (e) {
			log.error "Error performing stop on VM: ${e}", e
			return ServiceResponse.error("Error performing stop on VM: ${e}")
		}
	}

	/**
	 * Start the server
	 * @param computeServer to start
	 * @return Response from API
	 */
	@Override
	ServiceResponse startServer(ComputeServer computeServer) {
		try {
			HttpApiClient client = new HttpApiClient()
			Map authConfig = plugin.getAuthConfig(computeServer.cloud)

			return ProxmoxApiComputeUtil.startVM(client, authConfig, computeServer.parentServer.name, computeServer.externalId)
		} catch (e) {
			log.error "Error performing start on VM: ${e}", e
			return ServiceResponse.error("Error performing start on VM: ${e}")
		}
	}

	/**
	 * Returns the Morpheus Context for interacting with data stored in the Main Morpheus Application
	 *
	 * @return an implementation of the MorpheusContext for running Future based rxJava queries
	 */
	@Override
	MorpheusContext getMorpheus() {
		return this.@context
	}

	/**
	 * Returns the instance of the Plugin class that this provider is loaded from
	 * @return Plugin class contains references to other providers
	 */
	@Override
	Plugin getPlugin() {
		return this.@plugin
	}

	/**
	 * A unique shortcode used for referencing the provided provider. Make sure this is going to be unique as any data
	 * that is seeded or generated related to this provider will reference it by this code.
	 * @return short code string that should be unique across all other plugin implementations.
	 */
        @Override
        String getCode() {
                return 'proxmox-ve-provision'
        }

	/**
	 * Provides the provider name for reference when adding to the Morpheus Orchestrator
	 * NOTE: This may be useful to set as an i18n key for UI reference and localization support.
	 *
	 * @return either an English name of a Provider or an i18n based key that can be scanned for in a properties file.
	 */
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


	///MISSING LOGO issue
	////GOTCHA that needs to be fixed. The instanceType = instance-type.stackit in the scribe yml doesn't work
	@Override
	HostType getHostType() {
		HostType.vm
	}

	// ResizeFacet
	@Override
	ServiceResponse resizeWorkload(Instance instance, Workload workload, ResizeRequest resizeRequest, Map opts) {
		log.debug("resizeWorkload ${workload ? "workload" : "server"}.id: ${workload?.id ?: server?.id} - opts: ${opts}")

		// This method should handle hosts and VMs in future, so these are temp
		boolean isWorkload = true
		def server = workload.getServer()
		//

		ServiceResponse rtn = ServiceResponse.success()

		ComputeServer computeServer = context.async.computeServer.get(server.id).blockingGet()
		def authConfigMap = plugin.getAuthConfig(computeServer.cloud)
		try {
			HttpApiClient resizeClient = new HttpApiClient()
			HttpApiClient rebootClient = new HttpApiClient()

			//Compute
			computeServer.status = 'resizing'
			computeServer = saveAndGet(computeServer)

			def requestedMemory = resizeRequest.maxMemory
			def requestedCores = resizeRequest?.maxCores

			def currentMemory
			def currentCores

			if (isWorkload) {
				currentMemory = workload.maxMemory ?: workload.getConfigProperty('maxMemory')?.toLong()
				currentCores = workload.maxCores ?: 1
			} else {
				currentMemory = computeServer.maxMemory ?: computeServer.getConfigProperty('maxMemory')?.toLong()
				currentCores = server.maxCores ?: 1
			}
			def neededMemory = requestedMemory - currentMemory
			def neededCores = (requestedCores ?: 1) - (currentCores ?: 1)
			def allocationSpecs = [externalId: computeServer.externalId, maxMemory: requestedMemory, maxCpu: requestedCores]
			if (neededMemory > 100000000l || neededMemory < -100000000l || neededCores != 0) {
				log.info("Resizing VM with specs: ${allocationSpecs}")
				log.info("Resizing vm: ${workload.getInstance().name} with $server.coresPerSocket cores and $server.maxMemory memory")
				//resizeVMCompute(HttpApiClient client, Map authConfig, String node, String vmId, Long cpu, Long ram)
				ProxmoxApiComputeUtil.resizeVMCompute(resizeClient, authConfigMap, computeServer.parentServer.name, computeServer.externalId, requestedCores, requestedMemory)
				ProxmoxApiComputeUtil.rebootVM(rebootClient, authConfigMap, computeServer.name, computeServer.externalId)
			}
		} catch (e) {
			log.error("Unable to resize workload: ${e.message}", e)
			computeServer.status = 'provisioned'
			if (!isWorkload)
				computeServer.statusMessage = "Unable to resize server: ${e.message}"
			computeServer = saveAndGet(computeServer)
			rtn.success = false
			def error = morpheus.services.localization.get("gomorpheus.provision.xenServer.error.resizeWorkload")
			rtn.setError(error)
		}
		return rtn
	}


	//BlockDeviceNameFacet
	/*
	@Override
	String[] getDiskNameList() {
		return new String[0]
	}

	@Override
	String getDiskName(int index) {
		return super.getDiskName(index)
	}

	@Override
	String getDiskName(int index, String platform) {
		return super.getDiskName(index, platform)
	}

	@Override
	String getDiskDisplayName(int index) {
		return super.getDiskDisplayName(index)
	}

	@Override
	String getDiskDisplayName(int index, String platform) {
		return super.getDiskDisplayName(index, platform)
	}

	 */

        @Override
        ServiceResponse validateHost(ComputeServer server, Map opts) {
                try {
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
