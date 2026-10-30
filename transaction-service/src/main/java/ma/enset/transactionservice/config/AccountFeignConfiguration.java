package ma.enset.transactionservice.config;
import feign.Retryer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
@Configuration(proxyBeanMethods = false)
public class AccountFeignConfiguration {
    @Bean Retryer accountRetryer() { return Retryer.NEVER_RETRY; }
    @Bean okhttp3.OkHttpClient accountHttpClient() {
        // Disable transport retries too, including ambiguous connection failures.
        return new okhttp3.OkHttpClient.Builder().retryOnConnectionFailure(false).build();
    }
    @Bean feign.Client accountFeignTransport(okhttp3.OkHttpClient client) {
        return new feign.okhttp.OkHttpClient(client);
    }
}
