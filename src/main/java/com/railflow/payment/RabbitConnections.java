package com.railflow.payment;

import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import java.io.IOException;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class RabbitConnections {
    private final ConnectionFactory factory;
    private final String password;

    public RabbitConnections(@Value("${railflow.rabbitmq.host:127.0.0.1}") String host,
            @Value("${railflow.rabbitmq.port:5672}") int port,
            @Value("${railflow.rabbitmq.username:railflow}") String username,
            @Value("${railflow.rabbitmq.password:}") String password) {
        this.password = password;
        factory = new ConnectionFactory();
        factory.setHost(host);
        factory.setPort(port);
        factory.setUsername(username);
        factory.setPassword(password);
        factory.setVirtualHost("/");
        factory.setConnectionTimeout(5000);
        factory.setHandshakeTimeout(10000);
        factory.setRequestedHeartbeat(30);
        factory.setAutomaticRecoveryEnabled(true);
        factory.setTopologyRecoveryEnabled(true);
        factory.setNetworkRecoveryInterval(5000);
    }

    public Connection open() throws IOException, TimeoutException {
        if (password.isBlank()) {
            throw new IllegalStateException("请先设置 RABBITMQ_PASSWORD");
        }
        return factory.newConnection("railflow-java");
    }
}
