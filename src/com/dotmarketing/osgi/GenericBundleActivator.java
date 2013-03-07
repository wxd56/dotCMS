package com.dotmarketing.osgi;

import com.dotmarketing.business.APILocator;
import com.dotmarketing.business.Interceptor;
import com.dotmarketing.portlets.workflows.actionlet.WorkFlowActionlet;
import com.dotmarketing.portlets.workflows.business.WorkflowAPI;
import com.dotmarketing.portlets.workflows.business.WorkflowAPIOsgiService;
import com.dotmarketing.quartz.QuartzUtils;
import com.dotmarketing.quartz.ScheduledTask;
import com.dotmarketing.util.Config;
import com.dotmarketing.util.Logger;
import com.dotmarketing.util.OSGIUtil;
import com.dotmarketing.util.VelocityUtil;
import com.liferay.portal.ejb.PortletManagerImpl;
import com.liferay.portal.ejb.PortletManagerUtil;
import com.liferay.portal.model.Portlet;
import com.liferay.util.SimpleCachePool;
import org.apache.catalina.core.ApplicationContext;
import org.apache.catalina.core.ApplicationContextFacade;
import org.apache.catalina.core.StandardContext;
import org.apache.catalina.core.StandardWrapper;
import org.apache.felix.http.proxy.DispatcherTracker;
import org.apache.struts.Globals;
import org.apache.struts.action.ActionForward;
import org.apache.struts.action.ActionMapping;
import org.apache.struts.config.ActionConfig;
import org.apache.struts.config.ForwardConfig;
import org.apache.struts.config.ModuleConfig;
import org.apache.struts.config.impl.ModuleConfigImpl;
import org.apache.velocity.tools.view.PrimitiveToolboxManager;
import org.apache.velocity.tools.view.ToolInfo;
import org.apache.velocity.tools.view.servlet.ServletToolboxManager;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;
import org.quartz.SchedulerException;

import javax.servlet.ServletContext;
import java.lang.reflect.Field;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * Created by Jonathan Gamba
 * Date: 7/23/12
 */
public abstract class GenericBundleActivator implements BundleActivator {

    private PrimitiveToolboxManager toolboxManager;
    private WorkflowAPIOsgiService workflowOsgiService;
    private Collection<ToolInfo> viewTools;
    private Collection<WorkFlowActionlet> actionlets;
    private Map<String, String> jobs;
    private Collection<ActionConfig> actions;
    private Collection<Portlet> portlets;
    private Map<String, StandardWrapper> servlets;
    private Collection preHooks;
    private Collection postHooks;
    private ActivatorUtil activatorUtil = new ActivatorUtil();

    private ClassLoader getFelixClassLoader () {
        return this.getClass().getClassLoader();
    }

    private ClassLoader getContextClassLoader () {
        //return ClassLoader.getSystemClassLoader();
        return Thread.currentThread().getContextClassLoader();
    }

    /**
     * Verify and initialize if necessary the required OSGI services to create plugins
     *
     * @param context
     */
    protected void initializeServices ( BundleContext context ) {

        forceHttpServiceLoading( context );
        //Forcing the loading of the ToolboxManager
        forceToolBoxLoading( context );
        //Forcing the loading of the WorkflowService
        forceWorkflowServiceLoading( context );
    }

    /**
     * Allow to this bundle/elements to be visible and accessible from the host classpath
     */
    protected void publishBundleServices ( BundleContext context ) {

        //Classloaders
        ClassLoader felixClassLoader = getFelixClassLoader();
        ClassLoader contextClassLoader = getContextClassLoader();

        //Create a new class loader where we can "combine" our classloaders
        CombinedLoader combinedLoader;
        if ( contextClassLoader instanceof CombinedLoader ) {
            combinedLoader = (CombinedLoader) contextClassLoader;
            combinedLoader.addLoader( felixClassLoader );
        } else {
            combinedLoader = new CombinedLoader( contextClassLoader );
            combinedLoader.addLoader( felixClassLoader );
        }

        //Force the loading of some classes that may be already loaded on the host classpath but we want to override with the ones on this bundle and we specified
        String overrideClasses = context.getBundle().getHeaders().get( "Override-Classes" );
        if ( overrideClasses != null ) {
            String[] forceOverride = overrideClasses.split( "," );
            for ( String classToOverride : forceOverride ) {
                try {
                    //Just loading the custom implementation will allows to override the one the classloader already had loaded
                    combinedLoader.loadClass( classToOverride.trim() );
                } catch ( ClassNotFoundException e ) {
                    e.printStackTrace();
                }
            }
        }

        //Use this new "combined" class loader
        Thread.currentThread().setContextClassLoader( combinedLoader );
    }

    /**
     * Is possible on certain scenarios to have our ToolManager without initialization, or most probably a ToolManager without
     * set our required services, so we need to force things a little bit here, and register those services if it is necessary.
     *
     * @param context
     */
    private void forceToolBoxLoading ( BundleContext context ) {

        ServiceReference serviceRefSelected = context.getServiceReference( PrimitiveToolboxManager.class.getName() );
        if ( serviceRefSelected == null ) {

            //Forcing the loading of the ToolboxManager
            ServletToolboxManager toolboxManager = (ServletToolboxManager) VelocityUtil.getToolboxManager();
            if ( toolboxManager != null ) {

                serviceRefSelected = context.getServiceReference( PrimitiveToolboxManager.class.getName() );
                if ( serviceRefSelected == null ) {
                    toolboxManager.registerService();
                }
            }
        }
    }

    /**
     * Is possible on certain scenarios to have our WorkflowAPI without initialization, or most probably a WorkflowAPI without
     * set our required services, so we need to force things a little bit here, and register those services if it is necessary.
     *
     * @param context
     */
    private void forceWorkflowServiceLoading ( BundleContext context ) {

        //Getting the service to register our Actionlet
        ServiceReference serviceRefSelected = context.getServiceReference( WorkflowAPIOsgiService.class.getName() );
        if ( serviceRefSelected == null ) {

            //Forcing the loading of the WorkflowService
            WorkflowAPI workflowAPI = APILocator.getWorkflowAPI();
            if ( workflowAPI != null ) {

                serviceRefSelected = context.getServiceReference( WorkflowAPIOsgiService.class.getName() );
                if ( serviceRefSelected == null ) {
                    //Forcing the registration of our required services
                    workflowAPI.registerBundleService();
                }
            }
        }
    }

    /**
     * Forcing the registry of the HttpService, usually need it when the felix framework is reloaded and we need to update the
     * bundle context of our already registered services.
     *
     * @param context
     */
    private void forceHttpServiceLoading ( BundleContext context ) {

        try {
            //Working with the http bridge
            if ( OSGIProxyServlet.servletConfig != null ) {//If it is null probably the servlet wasn't even been loaded...

                try {
                    OSGIProxyServlet.bundleContext.getBundle();
                } catch ( IllegalStateException e ) {
                    //If we are here is because we have an invalid bundle context, so we need to provide a new one
                    BundleContext httpBundle = context.getBundle( OSGIUtil.BUNDLE_HTTP_BRIDGE_ID ).getBundleContext();
                    OSGIProxyServlet.tracker = new DispatcherTracker( httpBundle, null, OSGIProxyServlet.servletConfig );
                    OSGIProxyServlet.tracker.open();
                    OSGIProxyServlet.bundleContext = httpBundle;
                }

            }
        } catch ( Exception e ) {
            e.printStackTrace();
        }
    }

    /**
     * Will inject this bundle context inside the dotCMS context
     *
     * @param name a reference class inside this bundle jar
     * @throws Exception
     */
    private void injectContext ( String name ) throws Exception {

        //Verify if the class is already in the system class loader
        Class currentClass = null;
        try {
            currentClass = Class.forName( name, true, ClassLoader.getSystemClassLoader() );
        } catch ( ClassNotFoundException e ) {
            //Do nothing, the class is not inside the system classloader
        }

        //Get the class from this bundle context
        Class clazz = Class.forName( name, true, getFelixClassLoader() );
        URL classURL = clazz.getProtectionDomain().getCodeSource().getLocation();

        //Verify if we have our UrlOsgiClassLoader on the main class loaders
        UrlOsgiClassLoader urlOsgiClassLoader = activatorUtil.findCustomURLLoader( ClassLoader.getSystemClassLoader() );
        if ( urlOsgiClassLoader != null ) {

            if ( urlOsgiClassLoader.contains( classURL ) ) {
                //The classloader and the class content is already in the system classloader, so we need to reload the jar contents
                urlOsgiClassLoader.reload( classURL );
            } else {
                urlOsgiClassLoader.addURL( classURL );
            }
        } else {

            if ( currentClass != null ) {
                if ( currentClass.getClassLoader() instanceof UrlOsgiClassLoader ) {
                    urlOsgiClassLoader = (UrlOsgiClassLoader) currentClass.getClassLoader();
                }
            }

            if ( urlOsgiClassLoader == null ) {
                //Getting the reference of a known class in order to get the base/main class loader
                Class baseClass = getContextClassLoader().loadClass( "org.quartz.Job" );
                //Creates our custom class loader in order to use it to inject the class code inside dotcms context
                urlOsgiClassLoader = new UrlOsgiClassLoader( classURL, baseClass.getClassLoader() );
            } else {
                //The classloader and the class content in already in the system classloader, so we need to reload the jar contents
                urlOsgiClassLoader.reload( classURL );
            }

            /*
            In order to inject the class code inside dotcms context this is the main part of the process,
            is required to insert our custom class loader inside dotcms class loaders hierarchy.
             */
            ClassLoader loader = activatorUtil.findFirstLoader( ClassLoader.getSystemClassLoader() );

            Field parentLoaderField = ClassLoader.class.getDeclaredField( "parent" );
            parentLoaderField.setAccessible( true );
            parentLoaderField.set( loader, urlOsgiClassLoader );
            parentLoaderField.setAccessible( false );
        }
    }

    //*******************************************************************
    //*******************************************************************
    //****************REGISTER SERVICES METHODS**************************
    //*******************************************************************
    //*******************************************************************

    /**
     * Register the portlets on the given configuration files
     *
     * @param xmls
     * @throws Exception
     */
    @SuppressWarnings ("unchecked")
    protected Collection<Portlet> registerPortlets ( String[] xmls ) throws Exception {

        portlets = PortletManagerUtil.initWAR( null, xmls );

        //For JSPPortlets we need to create servlets for its jps files
        for ( Portlet portlet : portlets ) {
            if ( portlet.getPortletClass().equals( "com.liferay.portlet.JSPPortlet" ) ) {

                Map initParams = portlet.getInitParams();
                String jspPath = (String) initParams.get( "view-jsp" );

                if ( !jspPath.startsWith( "/" ) ) {
                    jspPath = "/" + jspPath;
                }

                String servletMapping = "/html" + jspPath;
                //Create a Servlet for this jsp
                StandardWrapper servlet = activatorUtil.createServletFromJspPath( jspPath, servletMapping );
                servlets.put( servletMapping, servlet );
            }
        }

        return portlets;
    }

    /**
     * Method that will create and add an ActionForward to a ActionMapping,this call is mandatory for the creation as ActionForwards
     * because extra logic will be required for jsp forwards to work.
     *
     * @param actionMapping
     * @param name
     * @param path
     * @param redirect
     * @return
     * @throws Exception
     */
    protected ForwardConfig registerActionForward ( ActionMapping actionMapping, String name, String path, Boolean redirect ) throws Exception {

        if ( servlets == null ) {
            servlets = new HashMap<String, StandardWrapper>();
        }

        // Creating an ForwardConfig Instance
        ForwardConfig forwardConfig = new ActionForward( name, path, redirect );
        // Adding the ForwardConfig to the ActionConfig
        actionMapping.addForwardConfig( forwardConfig );

        if ( path.contains( ".jsp" ) ) {

            if ( !path.startsWith( "/" ) ) {
                path = "/" + path;
            }

            String servletMapping = "/html" + path;
            //Create a Servlet for this jsp
            StandardWrapper servlet = activatorUtil.createServletFromJspPath( path, servletMapping );
            servlets.put( servletMapping, servlet );
        }

        return forwardConfig;
    }

    /**
     * Register a given ActionMapping
     *
     * @param actionMapping
     * @throws Exception
     */
    protected void registerActionMapping ( ActionMapping actionMapping ) throws Exception {

        if ( actions == null ) {
            actions = new ArrayList<ActionConfig>();
        }

        String actionClassType = actionMapping.getType();

        //Will inject the action classes inside the dotCMS context
        injectContext( actionClassType );

        ModuleConfig moduleConfig = activatorUtil.getModuleConfig();
        //We need to unfreeze this module in order to add new action mappings
        activatorUtil.unfreeze( moduleConfig );

        //Adding the ActionConfig to the ForwardConfig
        moduleConfig.addActionConfig( actionMapping );
        //moduleConfig.freeze();

        actions.add( actionMapping );

        Logger.info( this, "Added Struts Action Mapping: " + actionClassType );
    }

    /**
     * Register a given Quartz Job scheduled task
     *
     * @param scheduledTask
     * @throws Exception
     */
    protected void scheduleQuartzJob ( ScheduledTask scheduledTask ) throws Exception {

        String jobName = scheduledTask.getJobName();
        String jobGroup = scheduledTask.getJobGroup();

        if ( jobs == null ) {
            jobs = new HashMap<String, String>();
        }

        //Will inject the job classes inside the dotCMS context
        injectContext( scheduledTask.getJavaClassName() );

        /*
        Schedules the given job in the quartz system, and depending on the sequentialScheduled
        property it will use the sequential of the standard scheduler.
         */
        QuartzUtils.scheduleTask( scheduledTask );
        jobs.put( jobName, jobGroup );

        Logger.info( this, "Added Quartz Job: " + jobName );
    }

    /**
     * Register a WorkFlowActionlet service
     *
     * @param context
     * @param actionlet
     */
    @SuppressWarnings ("unchecked")
    protected void registerActionlet ( BundleContext context, WorkFlowActionlet actionlet ) {

        //Getting the service to register our Actionlet
        ServiceReference serviceRefSelected = context.getServiceReference( WorkflowAPIOsgiService.class.getName() );
        if ( serviceRefSelected == null ) {
            return;
        }

        if ( actionlets == null ) {
            actionlets = new ArrayList<WorkFlowActionlet>();
        }

        this.workflowOsgiService = (WorkflowAPIOsgiService) context.getService( serviceRefSelected );
        this.workflowOsgiService.addActionlet( actionlet.getClass() );
        actionlets.add( actionlet );

        Logger.info( this, "Added actionlet: " + actionlet.getName() );
    }

    /**
     * Register a ViewTool service using a ToolInfo object
     *
     * @param context
     * @param info
     */
    @SuppressWarnings ("unchecked")
    protected void registerViewToolService ( BundleContext context, ToolInfo info ) {

        //Getting the service to register our ViewTool
        ServiceReference serviceRefSelected = context.getServiceReference( PrimitiveToolboxManager.class.getName() );
        if ( serviceRefSelected == null ) {
            return;
        }

        if ( viewTools == null ) {
            viewTools = new ArrayList<ToolInfo>();
        }

        this.toolboxManager = (PrimitiveToolboxManager) context.getService( serviceRefSelected );
        this.toolboxManager.addTool( info );
        viewTools.add( info );

        Logger.info( this, "Added View Tool: " + info.getKey() );
    }

    /**
     * Adds a hook to the end of the chain
     *
     * @param preHook
     * @throws Exception
     */
    protected void addPreHook ( Object preHook ) throws Exception {

        Interceptor interceptor = (Interceptor) APILocator.getContentletAPIntercepter();

        if ( preHooks == null ) {
            preHooks = new ArrayList();
        }

        interceptor.addPreHook( preHook );
        preHooks.add( preHook );
    }

    /**
     * Adds a hook to the end of the chain
     *
     * @param postHook
     * @throws Exception
     */
    protected void addPostHook ( Object postHook ) throws Exception {

        Interceptor interceptor = (Interceptor) APILocator.getContentletAPIntercepter();

        if ( postHooks == null ) {
            postHooks = new ArrayList();
        }

        interceptor.addPostHook( postHook );
        postHooks.add( postHook );
    }

    //*******************************************************************
    //*******************************************************************
    //****************UNREGISTER SERVICES METHODS************************
    //*******************************************************************
    //*******************************************************************

    /**
     * Utility method to unregister all the possible services and/or tools registered by this activator class.
     * Some how we have to try to clean up anything added on the deploy if this bundle.
     */
    protected void unregisterServices () throws Exception {

        unregisterActionlets();
        unregisterViewToolServices();
        unpublishBundleServices();
        unregisterPreHooks();
        unregisterPostHooks();
        unregisterQuartzJobs();
        unregisterActionMappings();
        unregisterPortles();
        unregisterServlets();
    }

    /**
     * Unpublish this bundle elements
     */
    protected void unpublishBundleServices () {

        //Get the current classloader
        ClassLoader contextClassLoader = getContextClassLoader();
        if ( contextClassLoader instanceof CombinedLoader ) {
            //Try to remove this class loader
            ClassLoader felixClassLoader = getFelixClassLoader();
            ((CombinedLoader) contextClassLoader).removeLoader( felixClassLoader );
        }
    }

    /**
     * Unregister the registered WorkFlowActionlet services
     */
    protected void unregisterActionlets () {

        if ( this.workflowOsgiService != null && actionlets != null ) {
            for ( WorkFlowActionlet actionlet : actionlets ) {

                this.workflowOsgiService.removeActionlet( actionlet.getClass().getCanonicalName() );
                Logger.info( this, "Removed actionlet: " + actionlet.getClass().getCanonicalName() );
            }
        }
    }

    /**
     * Unregister the registered ViewTool services
     */
    protected void unregisterViewToolServices () {

        if ( this.toolboxManager != null && viewTools != null ) {
            for ( ToolInfo toolInfo : viewTools ) {

                this.toolboxManager.removeTool( toolInfo );
                Logger.info( this, "Removed View Tool: " + toolInfo.getKey() );
            }
        }
    }

    /**
     * Unregister all the registered post hooks
     *
     * @throws Exception
     */
    protected void unregisterPostHooks () {

        if ( postHooks != null ) {

            Interceptor interceptor = (Interceptor) APILocator.getContentletAPIntercepter();
            for ( Object postHook : postHooks ) {
                interceptor.delPostHook( postHook );
            }
        }
    }

    /**
     * Unregister all the registered pre hooks
     *
     * @throws Exception
     */
    protected void unregisterPreHooks () {

        if ( preHooks != null ) {

            Interceptor interceptor = (Interceptor) APILocator.getContentletAPIntercepter();
            for ( Object preHook : preHooks ) {
                interceptor.delPreHook( preHook );
            }
        }
    }

    protected void unregisterActionMappings () throws Exception {

        if ( actions != null ) {

            ModuleConfig moduleConfig = activatorUtil.getModuleConfig();
            //We need to unfreeze this module in order to add new action mappings
            activatorUtil.unfreeze( moduleConfig );

            for ( ActionConfig actionConfig : actions ) {
                moduleConfig.removeActionConfig( actionConfig );
            }
            moduleConfig.freeze();
        }

    }

    /**
     * Unregister all the registered Quartz Jobs
     *
     * @throws SchedulerException
     */
    protected void unregisterQuartzJobs () throws Exception {

        if ( jobs != null ) {
            for ( String jobName : jobs.keySet() ) {
                QuartzUtils.removeJob( jobName, jobs.get( jobName ) );
            }

            /*UrlOsgiClassLoader loader = activatorUtil.findCustomURLLoader( ClassLoader.getSystemClassLoader() );
            if ( loader != null ) {
                loader.getInstrumentation().removeTransformer( loader.getTransformer() );
            }*/
        }
    }

    /**
     * Unregister all the registered Quartz Jobs
     *
     * @throws SchedulerException
     */
    protected void unregisterPortles () throws Exception {

        if ( portlets != null ) {

            String scpId = PortletManagerImpl.class.getName() + "." + "SHARED_KEY";
            Map portletsPool = (Map) SimpleCachePool.get( scpId );

            for ( Portlet portlet : portlets ) {
                portletsPool.remove( portlet.getPrimaryKey().getPortletId() );
            }
        }
    }

    /**
     * Unregister all the registered servlets and mappings
     *
     * @throws SchedulerException
     */
    protected void unregisterServlets () throws Exception {

        if ( servlets != null ) {
            StandardContext standardContext = activatorUtil.getStandardContext();

            for ( String mapping : servlets.keySet() ) {
                StandardWrapper servlet = servlets.get( mapping );

                //Remove the registered servlet and mapping
                standardContext.removeChild( servlet );
                standardContext.removeServletMapping( mapping );
            }
        }
    }

    class ActivatorUtil {

        public UrlOsgiClassLoader findCustomURLLoader ( ClassLoader loader ) {

            if ( loader == null ) {
                return null;
            } else if ( loader instanceof UrlOsgiClassLoader ) {
                return (UrlOsgiClassLoader) loader;
            } else {
                return findCustomURLLoader( loader.getParent() );
            }
        }

        public ClassLoader findFirstLoader ( ClassLoader loader ) {

            if ( loader.getParent() == null ) {
                return loader;
            } else {
                return findFirstLoader( loader.getParent() );
            }
        }

        public ModuleConfig getModuleConfig () {
            ServletContext servletContext = Config.CONTEXT;
            return (ModuleConfig) servletContext.getAttribute( Globals.MODULE_KEY );
        }

        public StandardContext getStandardContext () throws Exception {

            ServletContext servletContext = Config.CONTEXT;
            ApplicationContextFacade applicationContextFacade = ((ApplicationContextFacade) servletContext);

            Field contextField = ApplicationContextFacade.class.getDeclaredField( "context" );//We need to access it using reflection
            contextField.setAccessible( true );
            ApplicationContext applicationContext = (ApplicationContext) contextField.get( applicationContextFacade );
            contextField.setAccessible( false );

            contextField = ApplicationContext.class.getDeclaredField( "context" );//We need to access it using reflection
            contextField.setAccessible( true );
            StandardContext standardContext = (StandardContext) contextField.get( applicationContext );
            contextField.setAccessible( false );

            return standardContext;
        }

        public StandardWrapper createServletFromJspPath ( String jspPath, String servletMapping ) throws Exception {

            StandardContext standardContext = activatorUtil.getStandardContext();

            //We need to register our jsp as a servlet, That means we need to generate the name of the jsp after compilation
            String jspName = jspPath.substring( jspPath.lastIndexOf( "/" ) + 1, jspPath.length() );
            String compiledJspName = jspName.replace( "_", "_005f" ).replace( ".jsp", "_jsp" );

            String compiledJsp = jspPath.replace( "/", "." ).replace( jspName, compiledJspName );
            compiledJsp = "org.apache.jsp" + compiledJsp;

            //Create the servlet for the forward jsp file
            StandardWrapper servlet = new StandardWrapper();
            servlet.setServletClass( compiledJsp );
            servlet.setName( compiledJsp );
            standardContext.addChild( servlet );
            standardContext.addServletMapping( servletMapping, compiledJsp );

            return servlet;
        }

        public void unfreeze ( ModuleConfig moduleConfig ) throws NoSuchFieldException, IllegalAccessException {

            Field configuredField = ModuleConfigImpl.class.getDeclaredField( "configured" );//We need to access it using reflection
            configuredField.setAccessible( true );
            configuredField.set( moduleConfig, false );
            configuredField.setAccessible( false );
        }

    }

}