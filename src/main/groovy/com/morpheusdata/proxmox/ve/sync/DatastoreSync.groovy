package com.morpheusdata.proxmox.ve.sync

import com.morpheusdata.proxmox.ve.ProxmoxVePlugin
import com.morpheusdata.proxmox.ve.util.ProxmoxApiComputeUtil
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.util.HttpApiClient
import com.morpheusdata.core.util.SyncTask
import com.morpheusdata.model.Account
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.Datastore
import com.morpheusdata.model.StorageVolume
import com.morpheusdata.model.projection.DatastoreIdentity
import groovy.util.logging.Slf4j



@Slf4j
class DatastoreSync {

    private Cloud cloud
    private MorpheusContext morpheusContext
    private ProxmoxVePlugin plugin
    private HttpApiClient apiClient
    private Map authConfig


    public DatastoreSync(ProxmoxVePlugin proxmoxVePlugin, Cloud cloud, HttpApiClient apiClient) {
        this.@plugin = proxmoxVePlugin
        this.@cloud = cloud
        this.@morpheusContext = proxmoxVePlugin.morpheus
        this.@apiClient = apiClient
        this.@authConfig = plugin.getAuthConfig(cloud)
    }



    def execute() {
        log.debug "Datastore Sync STARTED: ${cloud.id}"

        try {
            def datastoreResults = ProxmoxApiComputeUtil.listProxmoxDatastores(apiClient, authConfig)
            log.debug("Datastore list results: $datastoreResults")
            
            if (datastoreResults.success) {
                def cloudItems = datastoreResults?.data
                def domainRecords = morpheusContext.async.cloud.datastore.listSyncProjections(cloud.id)

                SyncTask<DatastoreIdentity, Map, StorageVolume> syncTask = new SyncTask<>(domainRecords, cloudItems as Collection)
                syncTask.addMatchFunction { DatastoreIdentity domainObject, Map cloudItem ->
                    domainObject.externalId == cloudItem.storage
                }.withLoadObjectDetails { List<SyncTask.UpdateItemDto<DatastoreIdentity, Map>> updateItems ->
                    Map<Long, SyncTask.UpdateItemDto<DatastoreIdentity, Map>> updateItemMap = updateItems.collectEntries { [(it.existingItem.id): it]}
                    return morpheusContext.async.cloud.datastore.listById(updateItems?.collect { it.existingItem.id }).map { Datastore datastore ->
                        return new SyncTask.UpdateItem<Datastore, Map>(existingItem: datastore, masterItem: updateItemMap[datastore.id].masterItem)
                    }            
                }.onAdd { itemsToAdd ->
                    addMissingDatastores(cloud, itemsToAdd)
                }.onUpdate { List<SyncTask.UpdateItem<Datastore, Map>> updateItems ->
                    updateMatchedDatastores(cloud, updateItems)
                }.onDelete { removeItems ->
                    removeMissingDatastores(cloud, removeItems)
                }.start()

            } else {
                log.error "Error in getting datastores: ${datastoreResults}"
            }
        } catch(e) {
            log.error "Error in DatastoreSync execute: ${e}", e
        }

        log.debug "Datastore Sync COMPLETED: ${cloud.id}"
    }


    private addMissingDatastores(Cloud cloud, Collection itemsToAdd) {
        log.debug "addMissingDatastores: ${cloud.name} ${itemsToAdd?.size()}"

        try {
            def adds = []
            itemsToAdd?.each { cloudItem ->
                log.debug("Adding datastore: $cloudItem")
                def datastoreConfig = [
                    owner       : new Account(id: cloud.owner.id),
                    name        : cloudItem.storage,
                    externalId  : cloudItem.storage,
                    cloud       : cloud,
                    storageSize : cloudItem.total?.toLong() ?: 0L,
                    freeSpace   : cloudItem.avail?.toLong() ?: 0L,
                    category    : "proxmox-ve-datastore.${cloud.id}",
                    drsEnabled  : false,
                    online      : true,
                    refType     : 'ComputeZone',
                    refId       : cloud.id,
                ]
                log.debug("Adding datastore: $datastoreConfig")
                Datastore add = new Datastore(datastoreConfig)
                adds << add
            }
            
            if (adds) {
                log.debug "Creating ${adds.size()} datastores"
                morpheusContext.async.cloud.datastore.create(adds).blockingGet()
            }
        } catch (e) {
            log.error "Error in addMissingDatastores: ${e}", e
        }
    }


    private updateMatchedDatastores(Cloud cloud, List<SyncTask.UpdateItem<Datastore, Map>> updateItems) {
        log.debug "updateMatchedDatastores: ${cloud.name} ${updateItems?.size()}"

        try {
            def saveList = []
            
            for (def updateItem in updateItems) {
                def existingItem = updateItem.existingItem
                def cloudItem = updateItem.masterItem
                def doUpdate = false

                // Update storage size
                def newStorageSize = cloudItem.total?.toLong() ?: 0L
                if (existingItem.storageSize != newStorageSize) {
                    log.debug("Updating storageSize for ${existingItem.name}: ${existingItem.storageSize} -> ${newStorageSize}")
                    existingItem.storageSize = newStorageSize
                    doUpdate = true
                }

                // Update free space
                def newFreeSpace = cloudItem.avail?.toLong() ?: 0L
                if (existingItem.freeSpace != newFreeSpace) {
                    log.debug("Updating freeSpace for ${existingItem.name}: ${existingItem.freeSpace} -> ${newFreeSpace}")
                    existingItem.freeSpace = newFreeSpace
                    doUpdate = true
                }

                // Update online status based on availability
                def isOnline = cloudItem.enabled != null ? cloudItem.enabled : true
                if (existingItem.online != isOnline) {
                    log.debug("Updating online status for ${existingItem.name}: ${existingItem.online} -> ${isOnline}")
                    existingItem.online = isOnline
                    doUpdate = true
                }

                // Update name if it has changed
                if (existingItem.name != cloudItem.storage) {
                    log.debug("Updating name for ${existingItem.name}: ${existingItem.name} -> ${cloudItem.storage}")
                    existingItem.name = cloudItem.storage
                    doUpdate = true
                }

                if (doUpdate) {
                    saveList << existingItem
                }
            }

            if (saveList) {
                log.debug "Saving ${saveList.size()} updated datastores"
                morpheusContext.async.cloud.datastore.save(saveList).blockingGet()
            }
        } catch (e) {
            log.error "Error in updateMatchedDatastores: ${e}", e
        }
    }


    private removeMissingDatastores(Cloud cloud, List<DatastoreIdentity> removeItems) {
        log.debug "removeMissingDatastores: ${cloud.name} ${removeItems?.size()}"
        
        try {
            if (removeItems) {
                log.info("Remove Datastores: ${removeItems.size()}")
                morpheusContext.async.cloud.datastore.bulkRemove(removeItems).blockingGet()
            }
        } catch (e) {
            log.error "Error in removeMissingDatastores: ${e}", e
        }
    }
}