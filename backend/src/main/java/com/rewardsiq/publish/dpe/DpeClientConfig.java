package com.rewardsiq.publish.dpe;

import com.rewardsiq.common.config.RewardsIqProperties;
import io.grpc.ManagedChannel;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import java.util.concurrent.TimeUnit;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** One long-lived channel to DPE, shut down with the application. */
@Configuration
public class DpeClientConfig {

    @Bean(destroyMethod = "")
    ManagedChannel dpeChannel(RewardsIqProperties props) {
        NettyChannelBuilder builder = NettyChannelBuilder.forTarget(props.dpe().target())
                .keepAliveTime(60, TimeUnit.SECONDS);
        if (props.dpe().plaintext()) {
            builder.usePlaintext(); // local stub only; real environments use TLS
        }
        return builder.build();
    }

    @Bean
    DpeGrpcPublisher dpeGrpcPublisher(ManagedChannel dpeChannel, RewardsIqProperties props) {
        return new DpeGrpcPublisher(dpeChannel, props.dpe());
    }

    @Bean
    ChannelCloser dpeChannelCloser(ManagedChannel dpeChannel) {
        return new ChannelCloser(dpeChannel);
    }

    /** Graceful shutdown: let in-flight publishes finish for up to 5 seconds. */
    static final class ChannelCloser implements org.springframework.beans.factory.DisposableBean {
        private final ManagedChannel channel;

        ChannelCloser(ManagedChannel channel) {
            this.channel = channel;
        }

        @Override
        public void destroy() throws InterruptedException {
            channel.shutdown();
            if (!channel.awaitTermination(5, TimeUnit.SECONDS)) {
                channel.shutdownNow();
            }
        }
    }
}
