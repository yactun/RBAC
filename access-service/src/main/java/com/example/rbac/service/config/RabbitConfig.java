package com.example.rbac.service.config;

import com.example.rbac.model.Bus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.ExchangeTypes;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The PDP declares the invalidation exchange (topic) and publishes to it.
 * Clients declare their own per-instance, non-durable, auto-delete queues
 * bound to the same exchange/routing key (see the starter's
 * InvalidationSubscriber). Names come from the shared {@link Bus} so both
 * sides agree.
 */
@Configuration
public class RabbitConfig {

    @Bean
    public TopicExchange invalidationExchange() {
        return ExchangeBuilder.topicExchange(Bus.EXCHANGE).durable(true).build();
    }

    @Bean
    public Queue pdpManagementQueue() {
        return QueueBuilder.durable(Bus.EXCHANGE + ".management").build();
    }

    @Bean
    public Binding managementBinding() {
        return BindingBuilder.bind(pdpManagementQueue()).to(invalidationExchange()).with(Bus.ROUTING_KEY);
    }

    @Bean
    public MessageConverter jsonMessageConverter(ObjectMapper json) {
        return new Jackson2JsonMessageConverter(json);
    }
}
