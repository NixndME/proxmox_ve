package com.morpheusdata.proxmox.ve.sync


import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.providers.CloudProvider
import com.morpheusdata.core.util.HttpApiClient
import com.morpheusdata.core.util.SyncTask
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.ComputeCapacityInfo
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.OsType
import com.morpheusdata.model.projection.ComputeServerIdentityProjection
import com.morpheusdata.proxmox.ve.ProxmoxVePlugin
import com.morpheusdata.proxmox.ve.util.ProxmoxApiComputeUtil
import groovy.util.logging.Slf4j

@Slf4j
class VMSync {

    private Cloud cloud
    private MorpheusContext context
    private ProxmoxVePlugin plugin
    private HttpApiClient apiClient
    private CloudProvider cloudProvider
    private Map authConfig


    VMSync(ProxmoxVePlugin proxmoxVePlugin, Cloud cloud, HttpApiClient apiClient, CloudProvider cloudProvider) {
        this.@plugin = proxmoxVePlugin
        this.@cloud = cloud
        this.@apiClient = apiClient
        this.@context = proxmoxVePlugin.morpheus
        this.@cloudProvider = cloudProvider
        this.@authConfig = plugin.getAuthConfig(cloud)
    }



    def execute() {
        try {
            log.debug "Execute VMSync STARTED: ${cloud.id}"
            def cloudItems = ProxmoxApiComputeUtil.listVMs(apiClient, authConfig).data
            def domainRecords = context.async.computeServer.listIdentityProjections(cloud.id, null).filter {
                it.computeServerTypeCode == 'proxmox-qemu-vm-unmanaged'
            }

            log.debug("VM cloudItems: ${cloudItems.collect { it.toString() }}")
            log.debug("VM domainObjects: ${domainRecords.map { "${it.externalId} - ${it.name}" }.toList().blockingGet()}")

            SyncTask<ComputeServerIdentityProjection, Map, ComputeServer> syncTask = new SyncTask<>(domainRecords, cloudItems)
            syncTask.addMatchFunction { ComputeServerIdentityProjection domainObject, Map cloudItem ->
                domainObject.externalId == cloudItem.vmid.toString()
            }.onAdd { itemsToAdd ->
                addMissingVirtualMachines(cloud, itemsToAdd)
            }.onDelete { itemsToDelete ->
                removeMissingVMs(itemsToDelete)
            }.withLoadObjectDetails { updateItems ->
                Map<Long, SyncTask.UpdateItemDto<ComputeServerIdentityProjection, Map>> updateItemMap = updateItems.collectEntries { [(it.existingItem.id): it]}
                return context.async.computeServer.listById(updateItems?.collect { it.existingItem.id }).map { ComputeServer server ->
                    return new SyncTask.UpdateItem<ComputeServer, Map>(existingItem: server, masterItem: updateItemMap[server.id].masterItem)
                } 
            }.onUpdate { itemsToUpdate ->
                updateMatchingVMs(itemsToUpdate)
            }.start()
        } catch(e) {
            log.error "Error in VMSync execute : ${e}", e
        }
        log.debug "Execute VMSync COMPLETED: ${cloud.id}"
    }


    private void addMissingVirtualMachines(Cloud cloud, Collection items) {
        log.info("Adding ${items?.size()} new VMs for Proxmox cloud ${cloud.name}")

        try {
            def newVMs = []

            
            def hostIdentitiesMap = context.async.computeServer.listIdentityProjections(cloud.id, null).filter {
                it.computeServerTypeCode == 'proxmox-ve-node'  // Correct host type
            }.toMap {it.externalId }.blockingGet()

            def computeServerType = cloudProvider.computeServerTypes.find {
                it.code == 'proxmox-qemu-vm-unmanaged'
            }

            if (!computeServerType) {
                log.error "ComputeServerType 'proxmox-qemu-vm-unmanaged' not found!"
                return
            }

            items.each { Map cloudItem ->
                try {
                    log.debug("Adding VM: $cloudItem")
                    def newVM = new ComputeServer(
                        account          : cloud.account,
                        externalId       : cloudItem.vmid.toString(),        
                        name             : cloudItem.name,
                        externalIp       : cloudItem.ip,
                        internalIp       : cloudItem.ip,
                        sshHost          : cloudItem.ip,
                        sshUsername      : 'root',
                        provision        : false,
                        cloud            : cloud,
                        lvmEnabled       : false,
                        managed          : false,
                        serverType       : 'vm',
                        status           : 'provisioned',
                        uniqueId         : cloudItem.vmid.toString(),        
                        powerState       : cloudItem.status == 'running' ? ComputeServer.PowerState.on : ComputeServer.PowerState.off,
                        maxMemory        : cloudItem.maxmem?.toLong(),
                        maxCores         : cloudItem.maxcpu?.toLong(),
                        coresPerSocket   : cloudItem.maxcpu?.toLong(),
                        parentServer     : hostIdentitiesMap[cloudItem.node],
                        osType           : 'unknown',
                        serverOs         : new OsType(code: 'unknown'),
                        category         : "proxmox.ve.vm.${cloud.id}",
                        computeServerType: computeServerType
                    )
                    newVMs << newVM
                } catch(e) {
                    log.error "Error preparing VM ${cloudItem.vmid}: ${e}", e
                }
            }
            
            if (newVMs) {
                log.debug("Creating ${newVMs.size()} VMs in batch")
                def createdVMs = context.async.computeServer.bulkCreate(newVMs).blockingGet()
                
                if (createdVMs) {
                    log.info("Successfully created ${newVMs.size()} VMs")
                    
                    // Update metrics for each VM after creation
                    newVMs.eachWithIndex { vm, index ->
                        def cloudItem = items[index]
                        updateMachineMetrics(
                                vm,
                                cloudItem.maxcpu?.toLong(),
                                cloudItem.maxdisk?.toLong(),
                                cloudItem.disk?.toLong(),
                                cloudItem.maxmem?.toLong(),
                                cloudItem.mem?.toLong(),
                                cloudItem.maxcpu?.toLong(),
                                (cloudItem.status == 'running') ? ComputeServer.PowerState.on : ComputeServer.PowerState.off
                        )
                    }
                } else {
                    log.error "Error in bulk creating VMs"
                }
            }
        } catch(e) {
            log.error "Error in addMissingVirtualMachines: ${e}", e
        }
    }


    private updateMatchingVMs(List<SyncTask.UpdateItem<ComputeServer, Map>> updateItems) {
        log.debug("Updating ${updateItems?.size()} VMs...")
        
        try {
            def saveList = []
            
            for (def updateItem in updateItems) {
                def existingItem = updateItem.existingItem
                def cloudItem = updateItem.masterItem
                def doUpdate = false

                
                
                if (cloudItem.name && cloudItem.name != existingItem.name) {
                    log.debug("Updating name for VM ${existingItem.externalId}: ${existingItem.name} -> ${cloudItem.name}")
                    existingItem.name = cloudItem.name
                    doUpdate = true
                }

                
                if (cloudItem.ip && cloudItem.ip != existingItem.externalIp) {
                    log.debug("Updating externalIp for VM ${existingItem.externalId}: ${existingItem.externalIp} -> ${cloudItem.ip}")
                    existingItem.setExternalIp(cloudItem.ip)
                    existingItem.setInternalIp(cloudItem.ip)
                    existingItem.sshHost = cloudItem.ip
                    doUpdate = true
                }

                
                def newPowerState = (cloudItem.status == 'running') ? ComputeServer.PowerState.on : ComputeServer.PowerState.off
                if (existingItem.powerState != newPowerState) {
                    log.debug("Updating powerState for VM ${existingItem.externalId}: ${existingItem.powerState} -> ${newPowerState}")
                    existingItem.powerState = newPowerState
                    doUpdate = true
                }

                
                def maxMemory = cloudItem.maxmem?.toLong()
                if (maxMemory && existingItem.maxMemory != maxMemory) {
                    log.debug("Updating maxMemory for VM ${existingItem.externalId}: ${existingItem.maxMemory} -> ${maxMemory}")
                    existingItem.maxMemory = maxMemory
                    doUpdate = true
                }

                def maxCores = cloudItem.maxcpu?.toLong()
                if (maxCores && existingItem.maxCores != maxCores) {
                    log.debug("Updating maxCores for VM ${existingItem.externalId}: ${existingItem.maxCores} -> ${maxCores}")
                    existingItem.maxCores = maxCores
                    doUpdate = true
                }

                if (doUpdate) {
                    saveList << existingItem
                }

                // Always update machine metrics
                updateMachineMetrics(
                        existingItem,
                        cloudItem.maxcpu?.toLong(),
                        cloudItem.maxdisk?.toLong(),
                        cloudItem.disk?.toLong(),
                        cloudItem.maxmem?.toLong(),
                        cloudItem.mem?.toLong(),
                        cloudItem.maxcpu?.toLong(),
                        newPowerState
                )
            }
            
            if (saveList) {
                log.debug "Saving ${saveList.size()} updated VMs"
                context.async.computeServer.bulkSave(saveList).blockingGet()
            }
        } catch(e) {
            log.error "Error in updateMatchingVMs: ${e}", e
        }
    }


    private removeMissingVMs(List<ComputeServerIdentityProjection> removeItems) {
        log.debug "removeMissingVMs: ${removeItems?.size()}"
        
        try {
            if (removeItems) {
                log.info("Remove VMs: ${removeItems.size()}")
                context.async.computeServer.bulkRemove(removeItems).blockingGet()
            }
        } catch(e) {
            log.error "Error in removeMissingVMs: ${e}", e
        }
    }


    private updateMachineMetrics(ComputeServer server, Long maxCores, Long maxStorage, Long usedStorage, Long maxMemory, Long usedMemory, Long maxCpu, ComputeServer.PowerState status) {
        log.debug "updateMachineMetrics for ${server?.name} (${server?.externalId})"
        
        try {
            def updates = !server.getComputeCapacityInfo()
            ComputeCapacityInfo capacityInfo = server.getComputeCapacityInfo() ?: new ComputeCapacityInfo()

            if(capacityInfo.maxCores != maxCores || server.maxCores != maxCores) {
                capacityInfo.maxCores = maxCores
                server?.maxCores = maxCores
                updates = true
            }

            if(capacityInfo.maxStorage != maxStorage || server.maxStorage != maxStorage) {
                capacityInfo.maxStorage = maxStorage
                server?.maxStorage = maxStorage
                updates = true
            }

            if(capacityInfo.usedStorage != usedStorage || server.usedStorage != usedStorage) {
                capacityInfo.usedStorage = usedStorage
                server?.usedStorage = usedStorage
                updates = true
            }

            if(capacityInfo.maxMemory != maxMemory || server.maxMemory != maxMemory) {
                capacityInfo?.maxMemory = maxMemory
                server?.maxMemory = maxMemory
                updates = true
            }

            if(capacityInfo.usedMemory != usedMemory || server.usedMemory != usedMemory) {
                capacityInfo?.usedMemory = usedMemory
                server?.usedMemory = usedMemory
                updates = true
            }

            if(capacityInfo.maxCpu != maxCpu || server.usedCpu != maxCpu) {
                capacityInfo?.maxCpu = maxCpu
                server?.usedCpu = maxCpu
                updates = true
            }

            // Update power state in capacity info
            if(server.powerState != status) {
                server.powerState = status
                updates = true
            }

            if(updates == true) {
                server.capacityInfo = capacityInfo
                context.async.computeServer.bulkSave([server]).blockingGet()
            }
        } catch(e) {
            log.warn("error updating VM metrics for ${server?.name} (${server?.externalId}): ${e}", e)
        }
    }
}