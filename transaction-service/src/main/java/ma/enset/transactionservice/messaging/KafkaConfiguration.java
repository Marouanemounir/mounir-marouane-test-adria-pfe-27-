package ma.enset.transactionservice.messaging;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaConfiguration {
    @Bean
    public NewTopic transactionCreatedTopic() {
        return TopicBuilder.name(TransactionEventPublisher.TOPIC).partitions(1).replicas(1).build();
    }
}
