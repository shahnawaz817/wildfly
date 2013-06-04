/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2012, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.jboss.as.webservices.service;

import static org.jboss.as.webservices.WSLogger.ROOT_LOGGER;

import java.util.List;

import javax.management.JMException;
import javax.management.MBeanServer;

import org.jboss.as.security.plugins.SecurityDomainContext;
import org.jboss.as.security.service.SecurityDomainService;
import org.jboss.as.server.deployment.DeploymentUnit;
import org.jboss.as.webservices.security.SecurityDomainContextAdaptor;
import org.jboss.as.webservices.util.WSServices;
import org.jboss.metadata.web.jboss.JBossWebMetaData;
import org.jboss.msc.inject.Injector;
import org.jboss.msc.service.Service;
import org.jboss.msc.service.ServiceBuilder;
import org.jboss.msc.service.ServiceBuilder.DependencyType;
import org.jboss.msc.service.ServiceController;
import org.jboss.msc.service.ServiceController.Mode;
import org.jboss.msc.service.ServiceName;
import org.jboss.msc.service.ServiceTarget;
import org.jboss.msc.service.StartContext;
import org.jboss.msc.service.StartException;
import org.jboss.msc.service.StopContext;
import org.jboss.msc.value.InjectedValue;
import org.jboss.security.SecurityConstants;
import org.jboss.security.SecurityUtil;
import org.jboss.ws.api.monitoring.RecordProcessor;
import org.jboss.ws.common.ObjectNameFactory;
import org.jboss.ws.common.management.ManagedEndpoint;
import org.jboss.ws.common.monitoring.ManagedRecordProcessor;
import org.jboss.wsf.spi.deployment.Endpoint;

/**
 * WS endpoint service; this is meant for setting the lazy deployment time info into the Endpoint (stuff coming from
 * dependencies upon other AS services that are started during the deployment)
 *
 * @author alessio.soldano@jboss.com
 * @author <a href="mailto:ropalka@redhat.com">Richard Opalka</a>
 * @author <a href="mailto:ema@redhat.com">Jim Ma</a>
 */
public final class EndpointService implements Service<Endpoint> {

    private static final ServiceName MBEAN_SERVER_NAME = ServiceName.JBOSS.append("mbean", "server");
    private final Endpoint endpoint;
    private final ServiceName name;
    private final InjectedValue<SecurityDomainContext> securityDomainContextValue = new InjectedValue<SecurityDomainContext>();
    private final InjectedValue<MBeanServer> mBeanServerValue = new InjectedValue<MBeanServer>();

    private EndpointService(final Endpoint endpoint, final ServiceName name) {
        this.endpoint = endpoint;
        this.name = name;
    }

    @Override
    public Endpoint getValue() {
        return endpoint;
    }

    public static ServiceName getServiceName(final DeploymentUnit unit, final String endpointName) {
        if (unit.getParent() != null) {
            return WSServices.ENDPOINT_SERVICE.append(unit.getParent().getName()).append(unit.getName()).append(endpointName);
        } else {
            return WSServices.ENDPOINT_SERVICE.append(unit.getName()).append(endpointName);
        }
    }

    @Override
    public void start(final StartContext context) throws StartException {
        ROOT_LOGGER.starting(name);
        endpoint.setSecurityDomainContext(new SecurityDomainContextAdaptor(securityDomainContextValue.getValue()));
        final List<RecordProcessor> processors = endpoint.getRecordProcessors();
        for (final RecordProcessor processor : processors) {
            registerRecordProcessor(processor, endpoint);
        }
        registerEndpoint(endpoint);
    }

    @Override
    public void stop(final StopContext context) {
        ROOT_LOGGER.stopping(name);
        endpoint.setSecurityDomainContext(null);
        unregisterEndpoint(endpoint);
        final List<RecordProcessor> processors = endpoint.getRecordProcessors();
        for (final RecordProcessor processor : processors) {
            unregisterRecordProcessor(processor, endpoint);
        }
    }

    private void registerEndpoint(final Endpoint ep) {
        MBeanServer mbeanServer = mBeanServerValue.getValue();
        if (mbeanServer != null) {
            try {
                ManagedEndpoint jmxEndpoint = new ManagedEndpoint(endpoint, mbeanServer);
                mbeanServer.registerMBean(jmxEndpoint, endpoint.getName());
            } catch (final JMException ex) {
                ROOT_LOGGER.trace("Cannot register endpoint with JMX server: " + ex.getMessage(), ex);
                ROOT_LOGGER.cannotRegisterEndpoint(endpoint.getShortName());
            }
        } else {
            ROOT_LOGGER.mBeanServerNotAvailable(endpoint.getShortName());
        }
    }

    private void unregisterEndpoint(final Endpoint ep) {
        MBeanServer mbeanServer = mBeanServerValue.getValue();
        if (mbeanServer != null) {
            try {
                mbeanServer.unregisterMBean(endpoint.getName());
            } catch (final JMException ex) {
                ROOT_LOGGER.trace("Cannot unregister endpoint with JMX server: " + ex.getMessage(), ex);
                ROOT_LOGGER.cannotUnregisterEndpoint(endpoint.getShortName());
            }
        } else {
            ROOT_LOGGER.mBeanServerNotAvailable(endpoint.getShortName());
        }
    }

    private void registerRecordProcessor(final RecordProcessor processor, final Endpoint ep) {
        MBeanServer mbeanServer = mBeanServerValue.getValue();
        if (mbeanServer != null) {
            try {
                mbeanServer.registerMBean(processor,
                        ObjectNameFactory.create(ep.getName() + ",recordProcessor=" + processor.getName()));
            } catch (final JMException ex) {
                ROOT_LOGGER.trace("Cannot register endpoint with JMX server, trying with the default ManagedRecordProcessor: "
                        + ex.getMessage());
                try {
                    mbeanServer.registerMBean(new ManagedRecordProcessor(processor),
                            ObjectNameFactory.create(ep.getName() + ",recordProcessor=" + processor.getName()));
                } catch (final JMException e) {
                    ROOT_LOGGER.cannotRegisterRecordProcessor();
                }
            }
        } else {
            ROOT_LOGGER.mBeanServerNotAvailable(processor);
        }
    }

    private void unregisterRecordProcessor(final RecordProcessor processor, final Endpoint ep) {
        MBeanServer mbeanServer = mBeanServerValue.getValue();
        if (mbeanServer != null) {
            try {
                mbeanServer.unregisterMBean(ObjectNameFactory.create(ep.getName() + ",recordProcessor=" + processor.getName()));
            } catch (final JMException e) {
                ROOT_LOGGER.cannotUnregisterRecordProcessor();
            }
        } else {
            ROOT_LOGGER.mBeanServerNotAvailable(processor);
        }
    }

    public Injector<SecurityDomainContext> getSecurityDomainContextInjector() {
        return securityDomainContextValue;
    }

    public Injector<MBeanServer> getMBeanServerInjector() {
        return mBeanServerValue;
    }

    public static void install(final ServiceTarget serviceTarget, final Endpoint endpoint, final DeploymentUnit unit) {
        final ServiceName serviceName = getServiceName(unit, endpoint.getShortName());
        final String propContext = endpoint.getName().getKeyProperty(Endpoint.SEPID_PROPERTY_CONTEXT);
        final String propEndpoint = endpoint.getName().getKeyProperty(Endpoint.SEPID_PROPERTY_ENDPOINT);
        final StringBuilder context = new StringBuilder(Endpoint.SEPID_PROPERTY_CONTEXT).append("=").append(propContext);
        final EndpointService service = new EndpointService(endpoint, serviceName);
        final ServiceBuilder<Endpoint> builder = serviceTarget.addService(serviceName, service);
        final ServiceName alias = WSServices.ENDPOINT_SERVICE.append(context.toString()).append(propEndpoint);
        builder.addAliases(alias);
        builder.addDependency(DependencyType.REQUIRED,
                SecurityDomainService.SERVICE_NAME.append(getDeploymentSecurityDomainName(endpoint)),
                SecurityDomainContext.class, service.getSecurityDomainContextInjector());
        builder.addDependency(DependencyType.OPTIONAL, MBEAN_SERVER_NAME, MBeanServer.class, service.getMBeanServerInjector());
        builder.setInitialMode(Mode.ACTIVE);
        builder.install();
    }

    public static void uninstall(final Endpoint endpoint, final DeploymentUnit unit) {
        final ServiceName serviceName = getServiceName(unit, endpoint.getShortName());
        final ServiceController<?> endpointService = WSServices.getContainerRegistry().getService(serviceName);
        if (endpointService != null) {
            endpointService.setMode(Mode.REMOVE);
        }
    }

    private static String getDeploymentSecurityDomainName(final Endpoint ep) {
        JBossWebMetaData metadata = ep.getService().getDeployment().getAttachment(JBossWebMetaData.class);
        String metaDataSecurityDomain = metadata != null ? metadata.getSecurityDomain() : null;
        return metaDataSecurityDomain == null ? SecurityConstants.DEFAULT_APPLICATION_POLICY : SecurityUtil
                .unprefixSecurityDomain(metaDataSecurityDomain.trim());
    }

}
