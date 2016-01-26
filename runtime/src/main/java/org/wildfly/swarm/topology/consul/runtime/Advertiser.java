package org.wildfly.swarm.topology.consul.runtime;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.orbitz.consul.AgentClient;
import com.orbitz.consul.Consul;
import com.orbitz.consul.NotRegisteredException;
import com.orbitz.consul.model.agent.ImmutableRegistration;
import org.jboss.msc.inject.Injector;
import org.jboss.msc.service.Service;
import org.jboss.msc.service.ServiceName;
import org.jboss.msc.service.StartContext;
import org.jboss.msc.service.StartException;
import org.jboss.msc.service.StopContext;
import org.jboss.msc.value.InjectedValue;
import org.wildfly.swarm.topology.runtime.Registration;

/** Service advertiser providing TTL checks for all registered deployments
 *
 * @author John Hovell
 * @author Bob McWhirter
 */
public class Advertiser implements Service<Advertiser>, Runnable {

    public static final ServiceName SERVICE_NAME = ConsulService.SERVICE_NAME.append("advertiser");

    private InjectedValue<AgentClient> agentClientInjector = new InjectedValue<>();

    private Map<String, List<String>> advertisements = new ConcurrentHashMap<>();

    private Thread thread;

    public Injector<AgentClient> getAgentClientInjector() {
        return this.agentClientInjector;
    }

    public void advertise(Registration registration) {
        if (this.advertisements.containsKey(registration.getName())) {
            return;
        }

        UUID uuid = UUID.randomUUID();

        AgentClient client = this.agentClientInjector.getValue();

        List<String> keys = new ArrayList<>();
        this.advertisements.put(registration.getName(), keys);

        for (Registration.EndPoint endPoint : registration.endPoints()) {
            String key = uuid.toString() + "-" + endPoint.getVisibility();
            com.orbitz.consul.model.agent.Registration consulReg = ImmutableRegistration.builder()
                    .address( endPoint.getAddress() )
                    .port( endPoint.getPort() )
                    .id( key )
                    .name( registration.getName() )
                    .addTags( endPoint.getVisibility().toString() )
                    .check( com.orbitz.consul.model.agent.Registration.RegCheck.ttl( 3L ))
                    .build();
            client.register( consulReg );
            keys.add(key);
        }
    }

    public void unadvertise(String name) {
        this.advertisements.remove(name);
    }

    @Override
    public void start(StartContext startContext) throws StartException {
        this.thread = new Thread(this);

        this.thread.start();
    }

    @Override
    public void stop(StopContext stopContext) {
        this.thread.interrupt();
    }

    @Override
    public Advertiser getValue() throws IllegalStateException, IllegalArgumentException {
        return this;
    }

    @Override
    public void run() {
        AgentClient client = this.agentClientInjector.getValue();
        while (true) {
            this.advertisements.values()
                    .stream()
                    .flatMap(e -> e.stream())
                    .forEach(e -> {
                        try {
                            client.pass(e);
                        } catch (NotRegisteredException e1) {
                            // ignore?
                            e1.printStackTrace();
                        }
                    });
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                e.printStackTrace();
                break;
            }
        }

    }
}
