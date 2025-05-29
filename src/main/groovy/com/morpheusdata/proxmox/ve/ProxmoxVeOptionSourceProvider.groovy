package com.morpheusdata.proxmox.ve

import com.morpheusdata.core.AbstractOptionSourceProvider
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.core.data.DataFilter
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.model.ImageType
import com.morpheusdata.model.projection.ComputeServerIdentityProjection
import groovy.util.logging.Slf4j

import com.morpheusdata.model.OptionType
import com.morpheusdata.model.Cloud
import io.reactivex.rxjava3.core.Observable

@Slf4j
class ProxmoxVeOptionSourceProvider extends AbstractOptionSourceProvider {

    ProxmoxVePlugin plugin
    MorpheusContext morpheusContext

    ProxmoxVeOptionSourceProvider(ProxmoxVePlugin plugin, MorpheusContext context) {
        this.plugin = plugin
        this.morpheusContext = context
    }

    @Override
    MorpheusContext getMorpheus() {
        return this.morpheusContext
    }

    @Override
    Plugin getPlugin() {
        return this.plugin
    }

    @Override
    String getCode() {
        return 'proxmox-ve-option-source'
    }

    @Override
    String getName() {
        return 'Proxmox VE Option Source'
    }

    @Override
    List<String> getMethodNames() {
        return new ArrayList<String>(['proxmoxVeProvisionImage', 'proxmoxVeNode'])
    }


    def proxmoxVeNode(args) {
        log.debug "proxmoxVeNode: ${args}"
        def cloudId = args?.size() > 0 ? args.getAt(0).zoneId.toLong() : null
        def options = []

        def domainRecords = morpheusContext.async.computeServer.listIdentityProjections(cloudId, null).filter {
            ComputeServerIdentityProjection projection ->
                if (projection.category == "proxmox.ve.host.$cloudId") {
                    return true
                }
                false
        }.blockingSubscribe() {
            options << [name: it.name, value: it.externalId]
        }

        if (options.size() > 0) {
            options = options.sort { it.name }
        }

        log.error("FOUND ${options.size()} ComputeServer Nodes...")
        return options
    }


    def proxmoxVeProvisionImage(args) {
        log.debug "proxmoxVeProvisionImage: ${args}"
        def cloudId = args?.size() > 0 ? args.getAt(0).zoneId.toLong() : null
        def accountId = args?.size() > 0 ? args.getAt(0).accountId.toLong() : null
        def locationExternalIds = []

        def options = []
        def invalidStatus = ['Saving', 'Failed', 'Converting']
        def syncedVirtualImageLocations = morpheusContext.async.virtualImage.location.listIdentityProjections(
                new DataQuery().
                        withFilter('refId', cloudId).
                        withFilter('category', 'proxmox.image')
        ).blockingSubscribe() {
            //if (it.deleted == false &&
            //    !(it.status in invalidStatus)) {
            if (morpheusContext.services.virtualImage.listById([it.virtualImage.id]).first().userUploaded) {
                options << [name: "$it.virtualImage.name (Uploaded)", value: it.virtualImage.id]
            } else {
                options << [name: it.virtualImage.name, value: it.virtualImage.id]
            }
                locationExternalIds << it.externalId
            log.info("External ID found: $it.externalId")
            //}
        }

        ImageType[] imageTypes = [ImageType.qcow2]
        def virtualImageIds = morpheusContext.async.virtualImage.listIdentityProjections(accountId, imageTypes).filter {
            it.deleted == false
        }.map{it.id}.toList().blockingGet()

        if(virtualImageIds.size() > 0) {

            def query = new DataQuery().withFilters([
                    new DataFilter('status', 'Active'),
                    new DataFilter('id', 'in', virtualImageIds),
                    new DataFilter('userUploaded', true)
            ])

            morpheusContext.async.virtualImage.list(query).blockingSubscribe {
                if (!(it.externalId in locationExternalIds)) {
                    log.info("Uploaded External ID found: $it.externalId ($it.name)")
                    options << [name: "$it.name (To Be Uploaded)", value: it.id]
                }
            }
        }

        if (options.size() > 0) {
            options = options.sort { it.name }
        }

        log.error("FOUND ${options.size()} VirtualImages...")
        return options
    }

    Observable<OptionType> getOptionTypes() {
        Collection<OptionType> optionTypes = []

        optionTypes << new OptionType([
            code: 'proxmox-ve-nodes',
            name: 'Proxmox Nodes',
            fieldName: 'nodeId',
            fieldContext: 'config',
            required: true,
            displayOrder: 10
        ])

        optionTypes << new OptionType([
            code: 'proxmox-ve-storage',
            name: 'Storage Pools',
            fieldName: 'storageId',
            fieldContext: 'config',
            required: true,
            displayOrder: 20
        ])

        optionTypes << new OptionType([
            code: 'proxmox-ve-networks',
            name: 'Network Bridges',
            fieldName: 'networkId',
            fieldContext: 'config',
            required: true,
            displayOrder: 30
        ])

        optionTypes << new OptionType([
            code: 'proxmox-ve-templates',
            name: 'VM Templates',
            fieldName: 'templateId',
            fieldContext: 'config',
            required: false,
            displayOrder: 40
        ])

        return Observable.fromIterable(optionTypes)
    }

    Observable<Map> getOptions(OptionType optionType, String searchTerm, Map config) {
        switch(optionType.code) {
            case 'proxmox-ve-nodes':
                return getProxmoxNodes(config, searchTerm)
            case 'proxmox-ve-storage':
                return getProxmoxStorage(config, searchTerm)
            case 'proxmox-ve-networks':
                return getProxmoxNetworks(config, searchTerm)
            case 'proxmox-ve-templates':
                return getProxmoxTemplates(config, searchTerm)
            default:
                return Observable.empty()
        }
    }

    private Observable<Map> getProxmoxNodes(Map config, String searchTerm) {
        try {
            def cloud = getCloudFromConfig(config)
            if (!cloud) return Observable.empty()

            def apiClient = new ProxmoxApiClient(morpheusContext, cloud, plugin)
            def nodes = apiClient.getClusterNodes()

            def options = nodes?.collect { node ->
                [name: node.node, value: node.node, id: node.node]
            } ?: []

            if (searchTerm) {
                options = options.findAll { it.name.toLowerCase().contains(searchTerm.toLowerCase()) }
            }

            return Observable.fromIterable(options)
        } catch (Exception e) {
            log.error("Error fetching Proxmox nodes: ${e.message}", e)
            return Observable.empty()
        }
    }

    private Observable<Map> getProxmoxStorage(Map config, String searchTerm) {
        try {
            def cloud = getCloudFromConfig(config)
            if (!cloud) return Observable.empty()

            def apiClient = new ProxmoxApiClient(morpheusContext, cloud, plugin)
            def nodes = apiClient.getClusterNodes()
            def storageOptions = []

            nodes?.each { node ->
                try {
                    def storage = apiClient.getNodeStorage(node.node)
                    storage.data?.each { store ->
                        if (store.enabled && store.content?.contains('images')) {
                            storageOptions << [
                                name: "${store.storage} (${node.node})",
                                value: store.storage,
                                id: "${node.node}:${store.storage}"
                            ]
                        }
                    }
                } catch (Exception nodeError) {
                    log.debug("Could not fetch storage for node ${node.node}: ${nodeError.message}")
                }
            }

            if (searchTerm) {
                storageOptions = storageOptions.findAll {
                    it.name.toLowerCase().contains(searchTerm.toLowerCase())
                }
            }

            return Observable.fromIterable(storageOptions)
        } catch (Exception e) {
            log.error("Error fetching Proxmox storage: ${e.message}", e)
            return Observable.empty()
        }
    }

    private Observable<Map> getProxmoxNetworks(Map config, String searchTerm) {
        try {
            def cloud = getCloudFromConfig(config)
            if (!cloud) return Observable.empty()

            def apiClient = new ProxmoxApiClient(morpheusContext, cloud, plugin)
            def nodes = apiClient.getClusterNodes()
            def networkOptions = []

            nodes?.each { node ->
                try {
                    def networks = apiClient.getNodeNetworks(node.node)
                    networks.data?.each { network ->
                        if (network.type == 'bridge') {
                            networkOptions << [
                                name: "${network.iface} (${node.node})",
                                value: network.iface,
                                id: "${node.node}:${network.iface}"
                            ]
                        }
                    }
                } catch (Exception nodeError) {
                    log.debug("Could not fetch networks for node ${node.node}: ${nodeError.message}")
                }
            }

            if (searchTerm) {
                networkOptions = networkOptions.findAll {
                    it.name.toLowerCase().contains(searchTerm.toLowerCase())
                }
            }

            return Observable.fromIterable(networkOptions)
        } catch (Exception e) {
            log.error("Error fetching Proxmox networks: ${e.message}", e)
            return Observable.empty()
        }
    }

    private Observable<Map> getProxmoxTemplates(Map config, String searchTerm) {
        try {
            def cloud = getCloudFromConfig(config)
            if (!cloud) return Observable.empty()

            def apiClient = new ProxmoxApiClient(morpheusContext, cloud, plugin)
            def nodes = apiClient.getClusterNodes()
            def templateOptions = []

            nodes?.each { node ->
                try {
                    def vms = apiClient.getNodeVms(node.node)
                    vms.data?.each { vm ->
                        if (vm.template == 1) {
                            templateOptions << [
                                name: "${vm.name} (${node.node})",
                                value: vm.vmid,
                                id: "${node.node}:${vm.vmid}"
                            ]
                        }
                    }
                } catch (Exception nodeError) {
                    log.debug("Could not fetch VMs for node ${node.node}: ${nodeError.message}")
                }
            }

            if (searchTerm) {
                templateOptions = templateOptions.findAll {
                    it.name.toLowerCase().contains(searchTerm.toLowerCase())
                }
            }

            return Observable.fromIterable(templateOptions)
        } catch (Exception e) {
            log.error("Error fetching Proxmox templates: ${e.message}", e)
            return Observable.empty()
        }
    }

    private Cloud getCloudFromConfig(Map config) {
        def cloudId = config.cloudId ?: config.zoneId
        if (cloudId) {
            return morpheusContext.async.cloud.get(cloudId as Long).blockingGet()
        }
        return null
    }

    def getVlanOptions(Map config, String searchTerm) {
        def options = []
        for (int i = 1; i <= 4094; i++) {
            if (!searchTerm || i.toString().contains(searchTerm)) {
                options << [
                        name : "VLAN ${i}",
                        value: i,
                        id   : i
                ]
            }
            if (options.size() >= 100) break
        }

        return Observable.fromIterable(options)
    }

    def getBondModeOptions() {
        def modes = [
                [name: 'Active-Backup', value: 'active-backup', id: 'active-backup'],
                [name: 'Balance XOR', value: 'balance-xor', id: 'balance-xor'],
                [name: 'Balance TLB', value: 'balance-tlb', id: 'balance-tlb'],
                [name: 'Balance ALB', value: 'balance-alb', id: 'balance-alb'],
                [name: '802.3ad LACP', value: '802.3ad', id: '802.3ad']
        ]

        return Observable.fromIterable(modes)
    }
}