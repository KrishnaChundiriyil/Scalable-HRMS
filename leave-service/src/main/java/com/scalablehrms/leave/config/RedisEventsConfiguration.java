package com.scalablehrms.leave.config;

import com.scalablehrms.leave.service.NotificationService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

@Configuration
public class RedisEventsConfiguration {
    @Bean
    RedisMessageListenerContainer eventSubscriber(RedisConnectionFactory connectionFactory,
                                                   NotificationService receiver) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(receiver, new ChannelTopic(NotificationService.channel()));
        return container;
    }
}
