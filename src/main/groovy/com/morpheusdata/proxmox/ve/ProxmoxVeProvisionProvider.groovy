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
import com.morpheusdata.proxmox.ve.util.ProxmoxMiscUtil
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
		// TODO: change icon paths to correct filenames once added to your project
		return new Icon(path:'provision-circular.svg', darkPath:'provision-circular-dark.svg')
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
		log.debug("In runWorkload...")

		log.debug("Cloud-Init User-Data User: $workloadRequest.cloudConfigUser")
		log.debug("Cloud-Init User-Data Network: $workloadRequest.cloudConfigNetwork")

		ComputeServer server = workload.server
		Cloud cloud = server.cloud
		VirtualImage virtualImage = server.sourceImage
		Map authConfig = plugin.getAuthConfig(cloud)
		HttpApiClient client = new HttpApiClient()
		String nodeId = workload.server.getConfigProperty('proxmoxNode') ?: null
		DatastoreIdentity targetDS = server.getVolumes().first().datastore

		if (!targetDS) {
			targetDS = getDefaultDatastore(cloud.id)
		}

		ComputeServer hvNode = getHypervisorHostByExternalId(cloud.id, nodeId)
		if (!hvNode.sshHost || !hvNode.sshUsername || !hvNode.sshPassword) {
			return ServiceResponse.error("SSH credentials required on host for provisioning to work. Edit the hypervisor host properties under the cloud Hosts tab.")
		}

		String imageExternalId = getOrUploadImage(client, authConfig, cloud, virtualImage, hvNode, targetDS.name)
		server.computeServerType = context.async.cloud.findComputeServerTypeByCode("proxmox-qemu-vm").blockingGet()
		server.serverOs = server.serverOs ?: virtualImage?.osType
		server.osType = (server.serverOs?.platform == PlatformType.windows ? 'windows' : 'linux') ?: virtualImage?.platform
		server.parentServer = hvNode
		server.osDevice = '/dev/sda'
		server.lvmEnabled = false
		server.status = 'provisioned'
		server.serverType = 'vm'
		server.managed = true
		server.discovered = false
		if(server.osType == 'windows') {
			server.guestConsoleType = ComputeServer.GuestConsoleType.rdp
		} else if(server.osType == 'linux') {
			server.guestConsoleType = ComputeServer.GuestConsoleType.ssh
		}
		server.account = cloud.getAccount()
		server.cloud = cloud
		server = saveAndGet(server)

		log.info("Provisioning/cloning: ${workload.getInstance().name} from Image Id: $imageExternalId on node: $nodeId")
		log.info("Provisioning/cloning: ${workload.getInstance().name} with $server.coresPerSocket cores and $server.maxMemory memory")
		ServiceResponse rtnClone = ProxmoxApiComputeUtil.cloneTemplate(client, authConfig, imageExternalId, workload.getInstance().name, nodeId, server.maxCores, server.maxMemory)
		log.debug("VM Clone done. Results: $rtnClone")

		server.internalId = rtnClone.data.vmId
		server.externalId = rtnClone.data.vmId
		server = saveAndGet(server)

		if (!rtnClone.success) {
			log.error("Provisioning/clone failed: $rtnClone.msg")
			return ServiceResponse.error("Provisioning failed: $rtnClone.msg")
		}

		def installAgentAfter = false
		log.debug("OPTS: $opts")
		if(virtualImage?.isCloudInit() && workloadRequest?.cloudConfigUser) {
			log.info(log.debug("Configuring Cloud-Init"))
			log.debug("Performing action on hypervisor node: $nodeId, $hvNode.sshHost, $hvNode.sshUsername")
			log.debug("Ensuring snippets directory on node: $nodeId")
			context.executeSshCommand(hvNode.sshHost, 22, hvNode.sshUsername, hvNode.sshPassword, "mkdir -p /var/lib/vz/snippets", "", "", "", false, LogLevel.info, true, null, false).blockingGet()
			log.debug("Creating cloud-init user-data file on hypervisor node: /var/lib/vz/snippets/${rtnClone.data.vmId}-cloud-init-user-data.yml")
			ProxmoxMiscUtil.sftpCreateFile(hvNode.sshHost, 22, hvNode.sshUsername, hvNode.sshPassword, "/var/lib/vz/snippets/${rtnClone.data.vmId}-cloud-init-user-data.yml", workloadRequest.cloudConfigUser, null)
			log.debug("Creating cloud-init user-data file on hypervisor node: /var/lib/vz/snippets/${rtnClone.data.vmId}-cloud-init-network.yml")
			ProxmoxMiscUtil.sftpCreateFile(hvNode.sshHost, 22, hvNode.sshUsername, hvNode.sshPassword, "/var/lib/vz/snippets/${rtnClone.data.vmId}-cloud-init-network.yml", workloadRequest.cloudConfigNetwork, null)
			log.debug("Creating cloud-init vm disk: local-zfs:cloudinit")
			context.executeSshCommand(hvNode.sshHost, 22, hvNode.sshUsername, hvNode.sshPassword, "qm set ${rtnClone.data.vmId} --ide2 local-zfs:cloudinit", "", "", "", false, LogLevel.info, true, null, false).blockingGet()
			log.debug("Mounting cloud-init data to disk...")
			String ciMountCommand = "qm set ${rtnClone.data.vmId} --cicustom \"user=local:snippets/${rtnClone.data.vmId}-cloud-init-user-data.yml,network=local:snippets/${rtnClone.data.vmId}-cloud-init-network.yml\""
			context.executeSshCommand(hvNode.sshHost, 22, hvNode.sshUsername, hvNode.sshPassword, ciMountCommand, "", "", "", false, LogLevel.info, true, null, false).blockingGet()
		} else {
			log.info("Non Cloud-Init deployment...")
			if (!opts.noAgent) {
				installAgentAfter = true
			}
		}

		ProxmoxApiComputeUtil.startVM(client, authConfig, nodeId, rtnClone.data.vmId)

		return new ServiceResponse<ProvisionResponse>(
				true,
				"Provisioned",
				null,
				new ProvisionResponse(
						success: true,
						skipNetworkWait: false,
						installAgent: installAgentAfter,
						externalId: server.externalId
				)
		)
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



	private getOrUploadImage(HttpApiClient client, Map authConfig, Cloud cloud, VirtualImage virtualImage, ComputeServer hvNode, String targetDS) {
		def imageExternalId
		def lock
		def imagePathPrefix = "/var/opt/morpheus/morpheus-ui/vms/morpheus-images"
		def remoteImageDir = "/var/lib/vz/template/qemu"
		def lockKey = "proxmox.ve.imageupload.${cloud.regionCode}.${virtualImage?.id}".toString()

		try {
			//hold up to a 1 hour lock for image upload
			lock = context.acquireLock(lockKey, [timeout: 2l * 60l * 1000l, ttl: 2l * 60l * 1000l]).blockingGet()
			if (virtualImage) {
				log.info("VIRTUAL IMAGE: Already Exists")
				VirtualImageLocation virtualImageLocation
				try {
					log.debug("searching for virtualImageLocation: $virtualImage.id")
					//virtualImageLocation = context.async.virtualImage.location.findVirtualImageLocation(virtualImage.id, cloud.id, cloud.regionCode, null, false).blockingGet()
					virtualImageLocation = context.async.virtualImage.location.find(new DataQuery().withFilters([
							new DataFilter("refType", "ComputeZone"),
							new DataFilter("refId", cloud.id),
							new DataFilter("externalId", virtualImage.externalId)
					])).blockingGet()
					log.debug("Got VirtualImageLocation ($cloud.id, $virtualImage.externalId): $virtualImageLocation")

					if (!virtualImageLocation) {
						log.info("VIRTUAL IMAGE: VirtualImageLocation doesn't exist")
						imageExternalId = null
					} else {
						log.info("VIRTUAL IMAGE: VirtualImageLocation already exists")
						imageExternalId = virtualImageLocation.externalId
					}
				} catch (e) {
					log.error "Error in findVirtualImageLocation.. could be not found ${e}", e
				}
			}
			if (!imageExternalId) { //If its userUploaded and still needs to be uploaded to cloud
				// Create the image
				def cloudFiles = context.async.virtualImage.getVirtualImageFiles(virtualImage).blockingGet()
				log.debug("CloudFiles: $cloudFiles")
				def imageFile = cloudFiles?.find { cloudFile -> cloudFile.name.toLowerCase().endsWith(".qcow2") }
				log.debug("ImageFile: $imageFile")
				def contentLength = imageFile?.getContentLength()

				//create qcow2 template directory on proxmox
				log.debug("Ensuring Image Directory on node: $hvNode.sshHost")
				def dirOut = context.executeSshCommand(hvNode.sshHost, 22, hvNode.sshUsername, hvNode.sshPassword, "mkdir -p $remoteImageDir", "", "", "", false, LogLevel.info, true, null, false).blockingGet()
				log.debug("Dir create SSH Task \"mkdir -p $remoteImageDir\" results: ${dirOut.toMap().toString()}")

				//sftp .qcow2 file to the directory on proxmox server
				log.debug("uploading Image $imagePathPrefix/$imageFile to $hvNode.sshHost:$remoteImageDir")
				ProxmoxMiscUtil.sftpUpload(hvNode.sshHost, 22, hvNode.sshUsername, hvNode.sshPassword, "$imagePathPrefix/$imageFile", remoteImageDir, null)

				//create blank vm template on proxmox
				ServiceResponse templateResp = ProxmoxApiComputeUtil.createImageTemplate(client, authConfig, virtualImage.name, hvNode.externalId, 1, 1024L)
				log.debug("Create Image response data $templateResp.data")
				imageExternalId = templateResp.data.templateId

				//import the disk file to the blank vm template
				String fileName = new File("$imageFile").getName()
				log.debug("Executing ImportDisk command on node: qm importdisk $templateResp.data.templateId $remoteImageDir/$fileName $targetDS")
				def diskCreateOut = context.executeSshCommand(hvNode.sshHost, 22, hvNode.sshUsername, hvNode.sshPassword, "qm importdisk $templateResp.data.templateId $remoteImageDir/$fileName $targetDS", "", "", "", false, LogLevel.info, true, null, false).blockingGet()
				log.debug("Disk ImportDisk SSH Task \"qm importdisk $templateResp.data.templateId $remoteImageDir/$fileName $targetDS\" results: ${diskCreateOut.toMap().toString()}")

				//Mount the disk
				log.debug("Executing DiskMount SSH Task \"qm set $templateResp.data.templateId --scsi0 $targetDS:vm-$templateResp.data.templateId-disk-0\"")
				def diskMountOut = context.executeSshCommand(hvNode.sshHost, 22, hvNode.sshUsername, hvNode.sshPassword, "qm set $templateResp.data.templateId --scsi0 $targetDS:vm-$templateResp.data.templateId-disk-0", "", "", "", false, LogLevel.info, true, null, false).blockingGet()
				log.debug("Disk Mount SSH Task \"qm set $templateResp.data.templateId --scsi0 $targetDS:vm-$templateResp.data.templateId-disk-0\" results: ${diskMountOut.toMap().toString()}")

				virtualImage.externalId = imageExternalId
				log.debug("Updating virtual image $virtualImage.name with external ID $virtualImage.externalId")
				context.async.virtualImage.bulkSave([virtualImage]).blockingGet()
				VirtualImageLocation virtualImageLocation = new VirtualImageLocation([
						virtualImage: virtualImage,
						externalId  : imageExternalId,
						imageRegion : cloud.regionCode,
						code        : "proxmox.ve.image.${cloud.id}.$templateResp.data.templateId",
						internalId  : imageExternalId,
						refId		: cloud.id,
						refType		: 'ComputeZone',
				])
				context.async.virtualImage.location.create([virtualImageLocation], cloud).blockingGet()

			}
		} finally {
			context.releaseLock(lockKey, [lock:lock]).blockingGet()
		}
		return imageExternalId
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
		return null
	}

	@Override
	ServiceResponse<PrepareHostResponse> prepareHost(ComputeServer server, HostRequest hostRequest, Map opts) {
		return null
	}

	@Override
	ServiceResponse<ProvisionResponse> runHost(ComputeServer server, HostRequest hostRequest, Map opts) {
		return null
	}

	@Override
	ServiceResponse finalizeHost(ComputeServer server) {
		return null
	}
}
