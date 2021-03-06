/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.felix.scr.impl.manager;

import java.lang.reflect.InvocationTargetException;
import java.security.Permission;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Dictionary;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.apache.felix.scr.impl.Activator;
import org.apache.felix.scr.impl.BundleComponentActivator;
import org.apache.felix.scr.impl.config.ComponentContainer;
import org.apache.felix.scr.impl.config.ComponentManager;
import org.apache.felix.scr.impl.config.ReferenceManager;
import org.apache.felix.scr.impl.config.ScrConfiguration;
import org.apache.felix.scr.impl.helper.ComponentMethods;
import org.apache.felix.scr.impl.helper.MethodResult;
import org.apache.felix.scr.impl.helper.SimpleLogger;
import org.apache.felix.scr.impl.metadata.ComponentMetadata;
import org.apache.felix.scr.impl.metadata.ReferenceMetadata;
import org.apache.felix.scr.impl.metadata.ServiceMetadata;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceException;
import org.osgi.framework.ServicePermission;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.component.ComponentConstants;
import org.osgi.service.log.LogService;
import org.osgi.util.promise.Deferred;
import org.osgi.util.promise.Promise;


/**
 * The default ComponentManager. Objects of this class are responsible for managing
 * implementation object's lifecycle.
 *
 */
public abstract class AbstractComponentManager<S> implements SimpleLogger, ComponentManager<S>
{
    //useful text for deactivation reason numbers
    static final String[] REASONS = {"Unspecified",
        "Component disabled",
        "Reference became unsatisfied",
        "Configuration modified",
        "Configuration deleted",
        "Component disabled",
        "Bundle stopped"};

    protected final ComponentContainer<S> m_container;

    //true for normal spec factory instances. False for "persistent" factory instances and obsolete use of factory component with factory configurations.
    protected final boolean m_factoryInstance;
    // the ID of this component
    private long m_componentId;

    private final ComponentMethods m_componentMethods;

    // The dependency managers that manage every dependency
    private final List<DependencyManager<S, ?>> m_dependencyManagers;

    private volatile boolean m_dependencyManagersInitialized;

    private final AtomicInteger m_trackingCount = new AtomicInteger( );

    // The ServiceRegistration is now tracked in the RegistrationManager

    private final ReentrantLock m_stateLock;

    /**
     * This latch prevents concurrent enable, disable, and reconfigure.  Since the enable and disable operations may use
     * two threads and the initiating thread does not wait for the operation to complete, we can't use a regular lock.
     */
    private final AtomicReference< Deferred<Void>> m_enabledLatchRef = new AtomicReference<Deferred<Void>>( new Deferred<Void>() );

    protected volatile boolean m_internalEnabled;

	private volatile boolean m_satisfied;

    protected volatile boolean m_disposed;

    //service event tracking
    private int m_floor;

    private volatile int m_ceiling;

    private final Lock m_missingLock = new ReentrantLock();
    private final Condition m_missingCondition = m_missingLock.newCondition();
    private final Set<Integer> m_missing = new TreeSet<Integer>( );

    volatile boolean m_activated;

    protected final ReentrantReadWriteLock m_activationLock = new ReentrantReadWriteLock();

    /**
     * The constructor receives both the activator and the metadata
     *
     * @param container
     * @param componentMethods
     */
    protected AbstractComponentManager( ComponentContainer<S> container, ComponentMethods componentMethods )
    {
        this( container, componentMethods, false );
    }

    protected AbstractComponentManager( ComponentContainer<S> container, ComponentMethods componentMethods, boolean factoryInstance )
    {
        m_enabledLatchRef.get().resolve(null);
        m_factoryInstance = factoryInstance;
        m_container = container;
        m_componentMethods = componentMethods;
        m_componentId = -1;

        ComponentMetadata metadata = container.getComponentMetadata();

        m_dependencyManagers = loadDependencyManagers( metadata );

        m_stateLock = new ReentrantLock( true );

        // dump component details
        if ( isLogEnabled( LogService.LOG_DEBUG ) )
        {
            log(
                LogService.LOG_DEBUG,
                "Component {0} created: DS={1}, implementation={2}, immediate={3}, default-enabled={4}, factory={5}, configuration-policy={6}, activate={7}, deactivate={8}, modified={9} configuration-pid={10}",
                new Object[]
                    { metadata.getName(), metadata.getDSVersion(),
                        metadata.getImplementationClassName(), metadata.isImmediate(),
                        metadata.isEnabled(), metadata.getFactoryIdentifier(),
                        metadata.getConfigurationPolicy(), metadata.getActivate(), metadata.getDeactivate(),
                        metadata.getModified(), metadata.getConfigurationPid() }, null );

            if ( metadata.getServiceMetadata() != null )
            {
                log( LogService.LOG_DEBUG, "Component {0} Services: scope={1}, services={2}", new Object[]
                    { metadata.getName(), metadata.getServiceScope(),
                        Arrays.asList( metadata.getServiceMetadata().getProvides() ) }, null );
            }

            if ( metadata.getProperties() != null )
            {
                log( LogService.LOG_DEBUG, "Component {0} Properties: {1}", new Object[]
                    { metadata.getName(), metadata.getProperties() }, null );
            }
        }
    }

    final long getLockTimeout()
    {
        BundleComponentActivator activator = getActivator();
        if ( activator != null )
        {
            return activator.getConfiguration().lockTimeout();
        }
        return ScrConfiguration.DEFAULT_LOCK_TIMEOUT_MILLISECONDS;
    }

    private void obtainLock( Lock lock, String source )
    {
        try
        {
            if (!lock.tryLock( getLockTimeout(), TimeUnit.MILLISECONDS ) )
            {
                dumpThreads();
                throw new IllegalStateException( "Could not obtain lock" );
            }
        }
        catch ( InterruptedException e )
        {
            try
            {
                if (!lock.tryLock( getLockTimeout(), TimeUnit.MILLISECONDS ) )
                {
                    dumpThreads();
                    throw new IllegalStateException( "Could not obtain lock" );
                }
            }
            catch ( InterruptedException e1 )
            {
                Thread.currentThread().interrupt();
                //TODO is there a better exception to throw?
                throw new IllegalStateException( "Interrupted twice: Could not obtain lock" );
            }
            Thread.currentThread().interrupt();
        }
    }

    final void obtainActivationReadLock( String source )
    {
        obtainLock( m_activationLock.readLock(), source);
    }

    final void releaseActivationReadLock( String source )
    {
        m_activationLock.readLock().unlock();
    }

    final void obtainActivationWriteLock( String source )
    {
        obtainLock( m_activationLock.writeLock(), source);
    }

    final void releaseActivationWriteeLock( String source )
    {
        if ( m_activationLock.getWriteHoldCount() > 0 )
        {
            m_activationLock.writeLock().unlock();
        }
    }

    final void obtainStateLock( String source )
    {
        obtainLock( m_stateLock, source );
    }

    final void releaseStateLock( String source )
    {
        m_stateLock.unlock();
    }

    final boolean isStateLocked()
    {
        return m_stateLock.getHoldCount() > 0;
    }

    final void dumpThreads()
    {
        try
        {
            String dump = new ThreadDump().call();
            log( LogService.LOG_DEBUG, dump, null );
        }
        catch ( Throwable t )
        {
            log( LogService.LOG_DEBUG, "Could not dump threads", t );
        }
    }

    //service event tracking
    void tracked( int trackingCount )
    {
        m_missingLock.lock();
        try
        {
            if (trackingCount == m_floor + 1 )
            {
                m_floor++;
                m_missing.remove( trackingCount );
            }
            else if ( trackingCount < m_ceiling )
            {
                m_missing.remove( trackingCount );
            }
            if ( trackingCount > m_ceiling )
            {
                for (int i = m_ceiling + 1; i < trackingCount; i++ )
                {
                    m_missing.add( i );
                }
                m_ceiling = trackingCount;
            }
            m_missingCondition.signalAll();
        }
        finally
        {
            m_missingLock.unlock();
        }
    }

    /**
     * We effectively maintain the set of completely processed service event tracking counts.  This method waits for all events prior
     * to the parameter tracking count to complete, then returns.  See further documentation in EdgeInfo.
     * @param trackingCount
     */
    void waitForTracked( int trackingCount )
    {
        m_missingLock.lock();
        try
        {
            while ( m_ceiling  < trackingCount || ( !m_missing.isEmpty() && m_missing.iterator().next() < trackingCount))
            {
                log( LogService.LOG_DEBUG, "waitForTracked trackingCount: {0} ceiling: {1} missing: {2}",
                        new Object[] {trackingCount, m_ceiling, m_missing}, null );
                try
                {
                    if ( !doMissingWait())
                    {
                        return;
                    }
                }
                catch ( InterruptedException e )
                {
                    try
                    {
                        if ( !doMissingWait())
                        {
                            return;
                        }
                    }
                    catch ( InterruptedException e1 )
                    {
                        log( LogService.LOG_ERROR, "waitForTracked interrupted twice: {0} ceiling: {1} missing: {2},  Expect further errors",
                                new Object[] {trackingCount, m_ceiling, m_missing}, e1 );
                    }
                    Thread.currentThread().interrupt();
                }
            }
        }
        finally
        {
            m_missingLock.unlock();
        }
    }

    private boolean doMissingWait() throws InterruptedException
    {
        if ( !m_missingCondition.await( getLockTimeout(), TimeUnit.MILLISECONDS ))
        {
            log( LogService.LOG_ERROR, "waitForTracked timed out: {0} ceiling: {1} missing: {2},  Expect further errors",
                    new Object[] {m_trackingCount, m_ceiling, m_missing}, null );
            dumpThreads();
            m_missing.clear();
            return false;
        }
        return true;
    }

//---------- Component ID management

    void registerComponentId()
    {
        final BundleComponentActivator activator = getActivator();
        if ( activator != null )
        {
            this.m_componentId = activator.registerComponentId( this );
        }
    }


    void unregisterComponentId()
    {
        if ( this.m_componentId >= 0 )
        {
            final BundleComponentActivator activator = getActivator();
            if ( activator != null )
            {
                activator.unregisterComponentId( this );
            }
            this.m_componentId = -1;
        }
    }


    //---------- Asynchronous frontend to state change methods ----------------
    private static final AtomicLong taskCounter = new AtomicLong( );

    public final Promise<Void> enable( final boolean async )
    {
        Deferred<Void> enableLatch = null;
        try
        {
            enableLatch = enableLatchWait();
            enableInternal();
            if ( !async )
            {
                activateInternal( );
            }
        }
        finally
        {
            if ( !async )
            {
                enableLatch.resolve(null);
            }
        }

        if ( async )
        {
            final Deferred<Void> latch = enableLatch;
            getActivator().schedule( new Runnable()
            {

                long count = taskCounter.incrementAndGet();

                public void run()
                {
                    try
                    {
                        activateInternal( );
                    }
                    finally
                    {
                        latch.resolve(null);
                    }
                }

                @Override
                public String toString()
                {
                    return "Async Activate: " + getComponentMetadata().getName() + " id: " + count;
                }
            } );
        }
        return enableLatch.getPromise();
    }

    /**
     * Use a CountDownLatch as a non-reentrant "lock" that can be passed between threads.
     * This lock assures that enable, disable, and reconfigure operations do not overlap.
     *
     * @return the latch to count down when the operation is complete (in the calling or another thread)
     * @throws InterruptedException
     */
    Deferred<Void> enableLatchWait()
    {
        Deferred<Void> enabledLatch;
        Deferred<Void> newEnabledLatch;
        do
        {
            enabledLatch = m_enabledLatchRef.get();
            boolean waited = false;
            boolean interrupted = false;
            while ( !waited )
            {
                try
                {
                    enabledLatch.getPromise().getValue();
                    waited = true;
                }
                catch ( InterruptedException e )
                {
                    interrupted = true;
                }
                catch (InvocationTargetException e)
                {
                    //this is not going to happen
                }
            }
            if ( interrupted )
            {
                Thread.currentThread().interrupt();
            }
            newEnabledLatch = new Deferred<Void>();
        }
        while ( !m_enabledLatchRef.compareAndSet( enabledLatch, newEnabledLatch) );
        return newEnabledLatch;
    }

    public final Promise<Void> disable( final boolean async )
    {
        Deferred<Void> enableLatch = null;
        try
        {
            enableLatch = enableLatchWait();
            if ( !async )
            {
                deactivateInternal( ComponentConstants.DEACTIVATION_REASON_DISABLED, true, false );
            }
            disableInternal();
        }
        finally
        {
            if (!async)
            {
                enableLatch.resolve(null);
            }
        }

        if ( async )
        {
            final Deferred<Void> latch = enableLatch;
            getActivator().schedule( new Runnable()
            {

                long count = taskCounter.incrementAndGet();

                public void run()
                {
                    try
                    {
                        deactivateInternal( ComponentConstants.DEACTIVATION_REASON_DISABLED, true, false );
                    }
                    finally
                    {
                        latch.resolve(null);
                    }
                }

                @Override
                public String toString()
                {
                    return "Async Deactivate: " + getComponentMetadata().getName() + " id: " + count;
                }

            } );
        }
        return enableLatch.getPromise();
    }

    // supports the ComponentInstance.dispose() method
    void dispose()
    {
        dispose( ComponentConstants.DEACTIVATION_REASON_DISPOSED );
    }

    /**
     * Disposes off this component deactivating and disabling it first as
     * required. After disposing off the component, it may not be used anymore.
     * <p>
     * This method unlike the other state change methods immediately takes
     * action and disposes the component. The reason for this is, that this
     * method has to actually complete before other actions like bundle stopping
     * may continue.
     */
    public void dispose( int reason )
    {
        deactivateInternal( reason, true, true );
    }

    <T> void registerMissingDependency( DependencyManager<S, T> dm, ServiceReference<T> ref, int trackingCount)
    {
        BundleComponentActivator activator = getActivator();
        if ( activator != null )
        {
            activator.registerMissingDependency( dm, ref, trackingCount );
        }
    }

    //---------- Component interface ------------------------------------------

    public long getId()
    {
        return m_componentId;
    }

    protected String getName() {
        return getComponentMetadata().getName();
    }

    /**
     * Returns the <code>Bundle</code> providing this component. If the
     * component as already been disposed off, this method returns
     * <code>null</code>.
     */
    public Bundle getBundle()
    {
        final BundleContext context = getBundleContext();
        if ( context != null )
        {
            try
            {
                return context.getBundle();
            }
            catch ( IllegalStateException ise )
            {
                // if the bundle context is not valid any more
            }
        }
        // already disposed off component or bundle context is invalid
        return null;
    }

    BundleContext getBundleContext()
    {
        final BundleComponentActivator activator = getActivator();
        if ( activator != null )
        {
            return activator.getBundleContext();
        }
        return null;
    }


    protected boolean isImmediate()
    {
        return getComponentMetadata().isImmediate();

    }

    public boolean isFactory()
    {
        return false;
    }

    protected boolean isSatisfied()
    {
        return m_satisfied;
    }


    //-------------- atomic transition methods -------------------------------

    final void enableInternal()
    {
        if ( m_disposed )
        {
            throw new IllegalStateException( "enable: " + this );
        }
        if ( !isActivatorActive() )
        {
            log( LogService.LOG_DEBUG, "Bundle's component activator is not active; not enabling component",
                    null );
            return;
        }

        registerComponentId();
        log( LogService.LOG_DEBUG, "Updating target filters", null );
        updateTargets( getProperties() );

        m_internalEnabled = true;
        log( LogService.LOG_DEBUG, "Component enabled", null );
    }

    final void activateInternal( )
    {
        log( LogService.LOG_DEBUG, "ActivateInternal",
                null );
        if ( m_disposed )
        {
            log( LogService.LOG_DEBUG, "ActivateInternal: disposed",
                    null );
            return;
        }
        if ( m_activated ) {
            log( LogService.LOG_DEBUG, "ActivateInternal: already activated",
                    null );
            return;
        }
        if ( !isInternalEnabled())
        {
            log( LogService.LOG_DEBUG, "Component is not enabled; not activating component",
                    null );
            return;
        }
        if ( !isActivatorActive() )
        {
            log( LogService.LOG_DEBUG, "Bundle's component activator is not active; not activating component",
                    null );
            return;
        }

        log( LogService.LOG_DEBUG, "Activating component from state {0}", new Object[] {getState()},  null );

        // Before creating the implementation object, we are going to
        // test that the bundle has enough permissions to register services
        if ( !hasServiceRegistrationPermissions() )
        {
            log( LogService.LOG_DEBUG, "Component is not permitted to register all services, cannot activate",
                    null );
            return;
        }

        obtainActivationReadLock( "activateInternal" );
        try
        {
            // Double check conditions now that we have obtained the lock
            if ( m_disposed )
            {
                log( LogService.LOG_DEBUG, "ActivateInternal: disposed",
                        null );
                return;
            }
            if ( m_activated ) {
                log( LogService.LOG_DEBUG, "ActivateInternal: already activated",
                        null );
                return;
            }
            if ( !isInternalEnabled() )
            {
                log( LogService.LOG_DEBUG, "Component is not enabled; not activating component",
                        null );
                return;
            }
            // Before creating the implementation object, we are going to
            // test if all the mandatory dependencies are satisfied
            if ( !verifyDependencyManagers() )
            {
                log( LogService.LOG_DEBUG, "Not all dependencies satisfied, cannot activate", null );
                return;
            }

            if ( !registerService() )
            {
                //some other thread is activating us, or we got concurrently deactivated.
                return;
            }


            if ( ( isImmediate() || getComponentMetadata().isFactory() ) )
            {
                getServiceInternal();
            }
        }
        finally
        {
            releaseActivationReadLock( "activateInternal" );
        }
    }

    /**
     * Handles deactivating, disabling, and disposing a component manager. Deactivating a factory instance
     * always disables and disposes it.  Deactivating a factory disposes it.
     * @param reason reason for action
     * @param disable whether to also disable the manager
     * @param dispose whether to also dispose of the manager
     */
    final void deactivateInternal( int reason, boolean disable, boolean dispose )
    {
        synchronized ( this )
        {
            if ( m_disposed )
            {
                return;
            }
            m_disposed = dispose;
        }
        log( LogService.LOG_DEBUG, "Deactivating component", null );

        // catch any problems from deleting the component to prevent the
        // component to remain in the deactivating state !
        obtainActivationReadLock( "deactivateInternal" );
        try
        {
            doDeactivate( reason, disable || m_factoryInstance );
        }
        finally
        {
            releaseActivationReadLock( "deactivateInternal" );
        }
        if ( isFactory() || m_factoryInstance || dispose )
        {
            log( LogService.LOG_DEBUG, "Disposing component (reason: " + reason + ")", null );
            clear();
        }
    }

    private void doDeactivate( int reason, boolean disable )
    {
        try
        {
            if ( !unregisterService() )
            {
                log( LogService.LOG_DEBUG, "Component deactivation occuring on another thread", null );
            }
            obtainStateLock( "AbstractComponentManager.State.doDeactivate.1" );
            try
            {
            	m_satisfied = false;
                m_activated = false;
                deleteComponent( reason );
                deactivateDependencyManagers();
                if ( disable )
                {
                    disableDependencyManagers();
                }
            }
            finally
            {
                releaseStateLock( "AbstractComponentManager.State.doDeactivate.1" );
            }
        }
        catch ( Throwable t )
        {
            log( LogService.LOG_WARNING, "Component deactivation threw an exception", t );
        }
    }

    final void disableInternal()
    {
        m_internalEnabled = false;
        if ( m_disposed )
        {
            throw new IllegalStateException( "Cannot disable a disposed component " + getName() );
        }
        unregisterComponentId();
    }

    final ServiceReference<S> getServiceReference()
    {
        ServiceRegistration<S> reg = getServiceRegistration();
        if (reg != null)
        {
            return reg.getReference();
        }
        return null;
    }

    //---------- Component handling methods ----------------------------------

    protected abstract void deleteComponent( int reason );

    boolean getServiceInternal()
    {
        return false;
    }

    /**
     * All ComponentManagers are ServiceFactory instances
     *
     * @return this as a ServiceFactory.
     */
    private Object getService()
    {
        return this;
    }

    ComponentMethods getComponentMethods()
    {
        return m_componentMethods;
    }

    protected String[] getProvidedServices()
    {
        if ( getComponentMetadata().getServiceMetadata() != null )
        {
            String[] provides = getComponentMetadata().getServiceMetadata().getProvides();
            return provides;
        }
        return null;

    }

    private final RegistrationManager<ServiceRegistration<S>> registrationManager = new RegistrationManager<ServiceRegistration<S>>()
    {

        @Override
        ServiceRegistration<S> register(String[] services)
        {
            BundleContext bundleContext = getBundleContext();
            if (bundleContext == null)
            {
                return null;
            }
            final Dictionary<String, Object> serviceProperties = getServiceProperties();
            try {
				ServiceRegistration<S> serviceRegistration = (ServiceRegistration<S>) bundleContext
						.registerService(services, getService(),
								serviceProperties);
				return serviceRegistration;
			} catch (ServiceException e) {
				log(LogService.LOG_ERROR, "Unexpected error registering component service with properties {0}",
						new Object[] {serviceProperties}, e);
				return null;
			}
        }

        @Override
        void unregister(ServiceRegistration<S> serviceRegistration)
        {
            serviceRegistration.unregister();
        }

        @Override
        void log(int level, String message, Object[] arguments, Throwable ex)
        {
            AbstractComponentManager.this.log(level, message, arguments, ex);
        }

        @Override
        long getTimeout()
        {
            return getLockTimeout();
        }

        @Override
        void reportTimeout()
        {
            dumpThreads();
        }

    };

    /**
     * Registers the service on behalf of the component.
     *
     */
    protected boolean registerService()
    {
        String[] services = getProvidedServices();
        if ( services != null )
        {
            return registrationManager.changeRegistration( RegistrationManager.RegState.registered, services);
        }
        return true;
    }

    protected boolean unregisterService()
    {
        String[] services = getProvidedServices();
        if ( services != null )
        {
            return registrationManager.changeRegistration( RegistrationManager.RegState.unregistered, services );
        }
        return true;
    }


    AtomicInteger getTrackingCount()
    {
        return m_trackingCount;
    }


    private void initDependencyManagers()
    {
        if ( m_dependencyManagersInitialized )
        {
            return;
        }
        final Bundle bundle = getBundle();
        if (bundle == null)
        {
            log( LogService.LOG_ERROR, "bundle shut down while trying to load implementation object class", null );
            throw new IllegalStateException("bundle shut down while trying to load implementation object class");
        }
        Class<?> implementationObjectClass;
        try
        {
            implementationObjectClass = bundle.loadClass(
                    getComponentMetadata().getImplementationClassName() );
        }
        catch ( ClassNotFoundException e )
        {
            log( LogService.LOG_ERROR, "Could not load implementation object class {0}",
                    new Object[] {getComponentMetadata().getImplementationClassName()}, e );
            throw new IllegalStateException("Could not load implementation object class "
                    + getComponentMetadata().getImplementationClassName());
        }
        m_componentMethods.initComponentMethods( getComponentMetadata(), implementationObjectClass );

        for ( DependencyManager dependencyManager : m_dependencyManagers )
        {
            dependencyManager.initBindingMethods( m_componentMethods.getBindMethods( dependencyManager.getName() ) );
        }
        m_dependencyManagersInitialized = true;
    }

    /**
     * Collect and store in m_dependencies_map all the services for dependencies, outside of any locks.
     * @param componentContext possible instance key for prototype scope references
     *
     * @return true if all references can be collected,
     *   false if some dependency is no longer available.
     */
    protected boolean collectDependencies(ComponentContextImpl<S> componentContext)
    {
        initDependencyManagers();
        for ( DependencyManager<S, ?> dependencyManager : m_dependencyManagers )
        {
            if ( !dependencyManager.prebind(componentContext) )
            {
                //not actually satisfied any longer
                deactivateDependencyManagers();
                log( LogService.LOG_DEBUG, "Could not get required dependency for dependency manager: {0}",
                        new Object[] {dependencyManager.getName()}, null );
                return false;
            }
        }
        log( LogService.LOG_DEBUG, "This thread collected dependencies", null );
        return true;
    }

    /**
     * Invoke updated method
     * @return {@code true} if the component needs reactivation, {@code false} otherwise.
     */
    abstract <T> boolean invokeUpdatedMethod( DependencyManager<S, T> dependencyManager, RefPair<S, T> refPair, int trackingCount );

    abstract <T> void invokeBindMethod( DependencyManager<S, T> dependencyManager, RefPair<S, T> refPair, int trackingCount );

    abstract <T> void invokeUnbindMethod( DependencyManager<S, T> dependencyManager, RefPair<S, T> oldRefPair, int trackingCount );

    //**********************************************************************************************************
    public BundleComponentActivator getActivator()
    {
        return m_container.getActivator();
    }


    boolean isActivatorActive()
    {
        BundleComponentActivator activator = getActivator();
        return activator != null && activator.isActive();
    }


    final ServiceRegistration<S> getServiceRegistration()
    {
        return registrationManager.getServiceRegistration();
    }


    synchronized void clear()
    {
        // for some testing, the activator may be null
        if ( m_container.getActivator() != null )
        {
            m_container.getActivator().unregisterComponentId( this );
        }
    }

    /**
     * Returns <code>true</code> if logging for the given level is enabled.
     */
    public boolean isLogEnabled( int level )
    {
        return Activator.isLogEnabled( level );
    }


    public void log( int level, String message, Throwable ex )
    {
        BundleComponentActivator activator = getActivator();
        if ( activator != null )
        {
            activator.log( level, message, getComponentMetadata(), m_componentId, ex );
        }
    }

    public void log( int level, String message, Object[] arguments, Throwable ex )
    {
        BundleComponentActivator activator = getActivator();
        if ( activator != null )
        {
            activator.log( level, message, arguments, getComponentMetadata(), m_componentId, ex );
        }
    }


    @Override
    public String toString()
    {
        return "Component: " + getName() + " (" + getId() + ")";
    }


    private boolean hasServiceRegistrationPermissions()
    {
        boolean allowed = true;
        if ( System.getSecurityManager() != null )
        {
            final ServiceMetadata serviceMetadata = getComponentMetadata().getServiceMetadata();
            if ( serviceMetadata != null )
            {
                final String[] services = serviceMetadata.getProvides();
                if ( services != null && services.length > 0 )
                {
                    final Bundle bundle = getBundle();
                    for ( String service : services )
                    {
                        final Permission perm = new ServicePermission( service, ServicePermission.REGISTER );
                        if ( !bundle.hasPermission( perm ) )
                        {
                            log( LogService.LOG_DEBUG, "Permission to register service {0} is denied", new Object[]
                                    {service}, null );
                            allowed = false;
                        }
                    }
                }
            }
        }

        // no security manager or no services to register
        return allowed;
    }


    private List<DependencyManager<S, ?>> loadDependencyManagers( ComponentMetadata metadata )
    {
        List<DependencyManager<S, ?>> depMgrList = new ArrayList<DependencyManager<S, ?>>(metadata.getDependencies().size());

        // If this component has got dependencies, create dependency managers for each one of them.
        if ( metadata.getDependencies().size() != 0 )
        {
            int index = 0;
            for ( ReferenceMetadata currentdependency: metadata.getDependencies() )
            {
                DependencyManager<S, ?> depmanager = new DependencyManager( this, currentdependency, index++ );

                depMgrList.add( depmanager );
            }
        }

        return depMgrList;
    }

    final void updateTargets(Map<String, Object> properties)
    {
        for ( DependencyManager<S, ?> dm: getDependencyManagers() )
        {
            dm.setTargetFilter( properties );
        }
    }

    protected boolean verifyDependencyManagers()
    {
        // indicates whether all dependencies are satisfied
        boolean satisfied = true;

        for ( DependencyManager<S, ?> dm: getDependencyManagers() )
        {

            if ( !dm.hasGetPermission() )
            {
                // bundle has no service get permission
                if ( dm.isOptional() )
                {
                    log( LogService.LOG_DEBUG, "No permission to get optional dependency: {0}; assuming satisfied",
                        new Object[]
                            { dm.getName() }, null );
                }
                else
                {
                    log( LogService.LOG_DEBUG, "No permission to get mandatory dependency: {0}; assuming unsatisfied",
                        new Object[]
                            { dm.getName() }, null );
                    satisfied = false;
                }
            }
            else if ( !dm.isSatisfied() )
            {
                // bundle would have permission but there are not enough services
                log( LogService.LOG_DEBUG, "Dependency not satisfied: {0}", new Object[]
                    { dm.getName() }, null );
                satisfied = false;
            }
        }

        m_satisfied = satisfied;
        return satisfied;
    }

    /**
     * Returns an iterator over the {@link DependencyManager} objects
     * representing the declared references in declaration order
     */
    List<DependencyManager<S, ?>> getDependencyManagers()
    {
        return m_dependencyManagers;
    }

    public List<? extends ReferenceManager<S, ?>> getReferenceManagers()
    {
    	return m_dependencyManagers;
    }

    /**
     * Returns an iterator over the {@link DependencyManager} objects
     * representing the declared references in reversed declaration order
     */
    List<DependencyManager<S, ?>> getReversedDependencyManagers()
    {
        List<DependencyManager<S, ?>> list = new ArrayList<DependencyManager<S, ?>>( m_dependencyManagers );
        Collections.reverse( list );
        return list;
    }


    DependencyManager<S, ?> getDependencyManager(String name)
    {
        for ( ReferenceManager<S, ?> dm: getDependencyManagers() )
        {
            if ( name.equals(dm.getName()) )
            {
                return (DependencyManager<S, ?>) dm;
            }
        }

        // not found
        return null;
    }

    private void deactivateDependencyManagers()
    {
        log( LogService.LOG_DEBUG, "Deactivating dependency managers", null);
        for ( DependencyManager<S, ?> dm: getDependencyManagers() )
        {
            dm.deactivate();
        }
    }

    private void disableDependencyManagers()
    {
        log( LogService.LOG_DEBUG, "Disabling dependency managers", null);
        AtomicInteger trackingCount = new AtomicInteger();
        for ( DependencyManager<S, ?> dm: getDependencyManagers() )
        {
            dm.unregisterServiceListener( trackingCount );
        }
    }

    /* (non-Javadoc)
	 * @see org.apache.felix.scr.impl.manager.ComponentManager#getProperties()
	 */
    public abstract Map<String, Object> getProperties();

    public abstract void setServiceProperties( Dictionary<String, ?> serviceProperties );

    /**
     * Returns the subset of component properties to be used as service
     * properties. These properties are all component properties where property
     * name does not start with dot (.), properties which are considered
     * private.
     */
    public Dictionary<String, Object> getServiceProperties()
    {
        return copyTo( null, getProperties(), false );
    }

    /**
     * Copies the properties from the <code>source</code> <code>Dictionary</code>
     * into the <code>target</code> <code>Dictionary</code> except for private
     * properties (whose name has a leading dot) which are only copied if the
     * <code>allProps</code> parameter is <code>true</code>.
     *
     * @param target    The <code>Dictionary</code> into which to copy the
     *                  properties. If <code>null</code> a new <code>Hashtable</code> is
     *                  created.
     * @param source    The <code>Dictionary</code> providing the properties to
     *                  copy. If <code>null</code> or empty, nothing is copied.
     * @param allProps  Whether all properties (<code>true</code>) or only the
     *                  public properties (<code>false</code>) are to be copied.
     *
     * @return The <code>target</code> is returned, which may be empty if
     *         <code>source</code> is <code>null</code> or empty and
     *         <code>target</code> was <code>null</code> or all properties are
     *         private and had not to be copied
     */
    protected static Dictionary<String, Object> copyTo( Dictionary<String, Object> target, final Map<String, ?> source, final boolean allProps )
    {
        if ( target == null )
        {
            target = new Hashtable<String, Object>();
        }

        if ( source != null && !source.isEmpty() )
        {
            for ( Map.Entry<String, ?> entry: source.entrySet() )
            {
                // cast is save, because key must be a string as per the spec
                String key = entry.getKey();
                if ( allProps || key.charAt( 0 ) != '.' )
                {
                    target.put( key, entry.getValue() );
                }
            }
        }

        return target;
    }

    /**
     * Copies the properties from the <code>source</code> <code>Dictionary</code>
     * into the <code>target</code> <code>Dictionary</code> except for private
     * properties (whose name has a leading dot) which are only copied if the
     * <code>allProps</code> parameter is <code>true</code>.
     * @param source    The <code>Dictionary</code> providing the properties to
     *                  copy. If <code>null</code> or empty, nothing is copied.
     * @param allProps  Whether all properties (<code>true</code>) or only the
     *                  public properties (<code>false</code>) are to be copied.
     *
     * @return The <code>target</code> is returned, which may be empty if
     *         <code>source</code> is <code>null</code> or empty and
     *         <code>target</code> was <code>null</code> or all properties are
     *         private and had not to be copied
     */
    protected static Map<String, Object> copyToMap( final Dictionary<String, ?> source, final boolean allProps )
    {
        Map<String, Object> target = new HashMap<String, Object>();

        if ( source != null && !source.isEmpty() )
        {
            for ( Enumeration<String> ce = source.keys(); ce.hasMoreElements(); )
            {
                // cast is save, because key must be a string as per the spec
                String key = ce.nextElement();
                if ( allProps || key.charAt( 0 ) != '.' )
                {
                    target.put( key, source.get( key ) );
                }
            }
        }

        return target;
    }

    protected static Dictionary<String, Object> copyToDictionary( final Dictionary<String, ?> source, final boolean allProps )
    {
        Hashtable<String, Object> target = new Hashtable<String, Object>();

        if ( source != null && !source.isEmpty() )
        {
            for ( Enumeration<String> ce = source.keys(); ce.hasMoreElements(); )
            {
                // cast is save, because key must be a string as per the spec
                String key = ce.nextElement();
                if ( allProps || key.charAt( 0 ) != '.' )
                {
                    target.put( key, source.get( key ) );
                }
            }
        }

        return target;
    }


    /**
     *
     */
    public ComponentMetadata getComponentMetadata()
    {
        return m_container.getComponentMetadata();
    }

    /**
     * TODO now returning bizarre mix of values!!
     */
    public int getState()
    {
        if (m_disposed)
        {
            return STATE_DISPOSED;
        }
        if ( !m_internalEnabled)
        {
            return STATE_DISABLED;
        }
        if ( !m_satisfied )
        {
            return STATE_UNSATISFIED_REFERENCE;
        }
        if ( hasInstance() )
        {
            return STATE_ACTIVE;
        }
        return STATE_SATISFIED;
    }

    abstract boolean hasInstance();

    public void setServiceProperties( MethodResult methodResult )
    {
        if ( methodResult.hasResult() )
        {
            Dictionary<String, Object> serviceProps = ( methodResult.getResult() == null) ? null : new Hashtable<String, Object>( methodResult.getResult() );
            setServiceProperties(serviceProps );
        }
    }

    boolean isInternalEnabled()
    {
        return m_internalEnabled;
    }

	public abstract void reconfigure(Map<String, Object> configuration, boolean configurationDeleted);

	public abstract void getComponentManagers(List<AbstractComponentManager<S>> cms);

}
