package org.openjava.gateway;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.embedded.netty.NettyReactiveWebServerFactory;
import org.springframework.cloud.circuitbreaker.resilience4j.ReactiveResilience4JCircuitBreakerFactory;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;

@SpringBootApplication
public class OpenjavaGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(OpenjavaGatewayApplication.class, args);
    }

    @Bean
    public RouteLocator routes(RouteLocatorBuilder builder) {
        return builder.routes()
            .route("rewrite_response_route", r -> r.path("/rewrite").filters(f -> f.prefixPath("/httpbin")
                .modifyResponseBody(String.class, String.class, (exchange, s) -> {
                    System.out.println("original response: " + s);
                    return Mono.just("here we go");
                }))
                .uri("https://www.baidu.com"))
            .build();
    }

    // 同时支持HTTP HTTPS，将HTTP重定向至HTTPS；重新启动一个NettyWebServer监听HTTP端口, 访问时重定向至HTTPS
    // @see https://blog.csdn.net/qq_38380025/article/details/106255171
    @Bean
    public ApplicationRunner applicationRunner() {
        return args -> {
            NettyReactiveWebServerFactory httpNettyReactiveWebServerFactory = new NettyReactiveWebServerFactory(8080);
            httpNettyReactiveWebServerFactory.getWebServer((request, response) -> {
                URI uri = request.getURI();
                URI httpsUri;
                try {
                    httpsUri = new URI("https", uri.getUserInfo(), uri.getHost(), 443, uri.getPath(), uri.getQuery(), uri.getFragment());
                } catch (URISyntaxException e) {
                    return Mono.error(e);
                }
                response.setStatusCode(HttpStatus.MOVED_PERMANENTLY);
                response.getHeaders().setLocation(httpsUri);
                return response.setComplete();
            }).start();
        };
    }

    // 所有GlobalFilter及GatewayFilter都已经在系统中注册，自定义Filter可以使用@Bean注册到系统中使用
    // CircuitBreaker GatewayFilter已经在系统中注册，这里只是全局配置CircuitBreaker
    @Bean
    public ReactiveResilience4JCircuitBreakerFactory defaultCustomizer() {
        // @see https://blog.csdn.net/yaomingyang/article/details/124635698
        CircuitBreakerConfig circuitBreakerConfig = CircuitBreakerConfig.custom()
            // 滑动窗口是COUNT_BASED，slidingWindowSize将会以次数为单位进行运算。滑动窗口是TIME_BASED，slidingWindowSize将以秒为单位进行运算
            .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.TIME_BASED) // 滑动窗口的类型为时间窗口
            .slidingWindowSize(10) // 时间窗口的大小为10秒
            .minimumNumberOfCalls(5) // 在单位时间窗口内最少需要5次调用才能开始进行统计计算
            .failureRateThreshold(10) // 在单位时间窗口内调用失败率达到10%后会启动断路器
            .enableAutomaticTransitionFromOpenToHalfOpen() // 允许断路器自动由打开状态转换为半开状态
            .permittedNumberOfCallsInHalfOpenState(10) // 在半开状态下允许进行正常调用的次数
            .waitDurationInOpenState(Duration.ofSeconds(5)) // 断路器打开状态转换为半开状态需要等待5秒
            .recordExceptions(Throwable.class) // 所有异常都当作失败来处理
            .slowCallDurationThreshold(Duration.ofMillis(200)) // 超过200毫秒判定为慢调用
            .slowCallRateThreshold(50.0f) //慢调用超过50%，断路器将打开
            .build();

        ReactiveResilience4JCircuitBreakerFactory factory = new ReactiveResilience4JCircuitBreakerFactory(
            CircuitBreakerRegistry.of(circuitBreakerConfig), TimeLimiterRegistry.of(TimeLimiterConfig.custom().timeoutDuration(Duration.ofMillis(200)).build()));
//        factory.configureDefault(id -> new Resilience4JConfigBuilder(id)
//            .timeLimiterConfig(TimeLimiterConfig.custom().timeoutDuration(Duration.ofMillis(200)).build())
//            .circuitBreakerConfig(circuitBreakerConfig).build());
        return factory;
    }
//@see https://docs.spring.io/spring-cloud-commons/docs/current/reference/html/#spring-cloud-loadbalancer
//详细了解loadbalace配置
}
