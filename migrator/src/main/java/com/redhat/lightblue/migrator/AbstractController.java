package com.redhat.lightblue.migrator;

import java.io.IOException;

import java.util.Date;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.redhat.lightblue.client.LightblueClient;
import com.redhat.lightblue.client.Query;
import com.redhat.lightblue.client.Projection;
import com.redhat.lightblue.client.request.data.DataInsertRequest;
import com.redhat.lightblue.client.request.data.DataDeleteRequest;
import com.redhat.lightblue.client.response.LightblueResponse;

public abstract class AbstractController extends Thread {

    private static final Logger LOGGER=LoggerFactory.getLogger(AbstractController.class);
    
    protected MigrationConfiguration migrationConfiguration;
    protected final Controller controller;
    protected final LightblueClient lbClient;
    protected final Class migratorClass;
    protected final ThreadGroup migratorThreads;

    public AbstractController(Controller controller,MigrationConfiguration migrationConfiguration,String threadGroupName) {
        this.migrationConfiguration=migrationConfiguration;
        this.controller=controller;
        lbClient=controller.getLightblueClient();
        if(migrationConfiguration.getMigratorClass()==null)
            migratorClass=DefaultMigrator.class;
        else
            try {
                migratorClass=Class.forName(migrationConfiguration.getMigratorClass());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        
        migratorThreads=new ThreadGroup(threadGroupName);
    }

    public ThreadGroup getMigratorThreads() {
        return migratorThreads;
    }

    public Controller getController() {
        return controller;
    }

    public MigrationConfiguration getMigrationConfiguration() {
        return migrationConfiguration;
    }

    public MigrationConfiguration reloadMigrationConfiguration() {
        try {
            LOGGER.debug("Reloading migration configuration {}",migrationConfiguration.get_id());
            return controller.loadMigrationConfiguration(migrationConfiguration.get_id());
        } catch (Exception e) {
            LOGGER.error("Cannot reload migration configuration:"+e);
        }
        return null;
    }

    /**
     * Attempts to lock a migration job. If successful, return the migration job and the active execution
     */
    public ActiveExecution lock(String id)
        throws Exception {
        DataInsertRequest insRequest=new DataInsertRequest("activeExecution",null);
        ActiveExecution ae=new ActiveExecution();
        ae.setMigrationJobId(id);
        ae.setStartTime(new Date());
        
        insRequest.create(ae);
        insRequest.returns(Projection.includeFieldRecursively("*"));
        LightblueResponse rsp;
        try {
            LOGGER.debug("Attempting to lock {}",ae.getMigrationJobId());
            rsp=lbClient.data(insRequest);
            LOGGER.debug("response:{}",rsp);
            if(rsp.hasError()) {
                LOGGER.debug("Response has error");
                return null;
            }
        } catch (Exception e) {
            LOGGER.debug("Error during insert:{}",e);
            return null;
        }
        if(rsp.parseModifiedCount()==1) {
            return rsp.parseProcessed(ActiveExecution.class);
        } else
            return null;
    }

    public void unlock(String id) {
        LOGGER.debug("Unlocking {}",id);
        DataDeleteRequest req=new DataDeleteRequest("activeExecution",null);
        req.where(Query.withValue("migrationJobId",Query.eq,id));
        try {
            LightblueResponse rsp=lbClient.data(req);
        } catch(Exception e) {
            LOGGER.error("Cannot delete lock {}",id);
        }
        Breakpoint.checkpoint("MigratorController:unlock");
    }

    public Migrator createMigrator(MigrationJob mj,ActiveExecution ae)
        throws Exception {
        Migrator migrator=(Migrator)migratorClass.getConstructor(ThreadGroup.class).newInstance(migratorThreads);
        migrator.setController(this);
        migrator.setMigrationJob(mj);
        migrator.setActiveExecution(ae);
        return migrator;
    }
}