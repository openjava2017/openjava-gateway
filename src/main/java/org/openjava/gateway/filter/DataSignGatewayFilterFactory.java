package org.openjava.gateway.filter;

import org.reactivestreams.Publisher;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.NettyWriteResponseFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.cloud.gateway.filter.factory.rewrite.CachedBodyOutputMessage;
import org.springframework.cloud.gateway.support.BodyInserterContext;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserter;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.server.HandlerStrategies;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.Random;

@Component
public class DataSignGatewayFilterFactory extends AbstractGatewayFilterFactory<DataSignGatewayFilterFactory.Config> {

    public DataSignGatewayFilterFactory() {
        super(Config.class);
    }

    @Override
    public GatewayFilter apply(Config config) {
        return new SignResponseGatewayFilter();
    }

    public class SignResponseGatewayFilter implements GatewayFilter, Ordered {
        @Override
        public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
            System.out.println("======" + exchange.getRequest().getId());
            return ServerWebExchangeUtils.cacheRequestBodyAndRequest(exchange, (serverHttpRequest) -> {
                final ServerRequest serverRequest = ServerRequest.create(exchange.mutate().request(serverHttpRequest).build(),
                        HandlerStrategies.withDefaults().messageReaders());
                final ServerHttpRequest.Builder builder = exchange.getRequest().mutate();
                serverRequest.bodyToMono(String.class).defaultIfEmpty("no body").subscribe(body -> {
                    // 签名
                    String signedBody = body;
                    builder.header("dili-sign", signedBody);
                });

                return chain.filter(exchange.mutate().request(builder.build()).response(new SignServerHttpResponse(exchange)).build());
            });
        }

        @Override
        public int getOrder() {
            // 必须指定合适的优先级，否则Filter无法生效
            return NettyWriteResponseFilter.WRITE_RESPONSE_FILTER_ORDER - 1;
        }
    }

    protected class SignServerHttpResponse extends ServerHttpResponseDecorator {
        private final ServerWebExchange exchange;

        public SignServerHttpResponse(ServerWebExchange exchange) {
            super(exchange.getResponse());
            this.exchange = exchange;
        }

        @Override
        public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
            ClientResponse response = ClientResponse.create(getStatusCode(), HandlerStrategies.withDefaults().messageReaders())
                .headers(headers -> headers.putAll(getHeaders())).body(Flux.from(body)).build();
            Mono<byte[]> modifiedBody = response.bodyToMono(byte[].class).defaultIfEmpty(new byte[0]).flatMap(bytes -> {
                // 根据服务端字符编码进行解码
//                String orignalBody = new String(bytes, Charset.forName("UTF-8"));
                // 进行验签
                String sign = getHeaders().getFirst("dili-sign");

                // 进行验签
                boolean verified = new Random().nextBoolean();;
                if (verified) {
                    return Mono.just(bytes);
                } else {
                    getHeaders().remove("Content-Encoding"); // remove Content-Encoding: gzip, 对于某些具体后端接口，可能不需要
                    getHeaders().setContentType(MediaType.APPLICATION_JSON);
                    return Mono.just("{\"code\": 202, \"message\": \"data verify failed\"}".getBytes(StandardCharsets.UTF_8));
                }
            });
            BodyInserter bodyInserter = BodyInserters.fromPublisher(modifiedBody, byte[].class);
            CachedBodyOutputMessage outputMessage = new CachedBodyOutputMessage(exchange, exchange.getResponse().getHeaders());

            return bodyInserter.insert(outputMessage, new BodyInserterContext()).then(Mono.defer(() -> {
                Mono<DataBuffer> messageBody = DataBufferUtils.join(outputMessage.getBody());
                HttpHeaders headers = getDelegate().getHeaders();
                if (!headers.containsKey(HttpHeaders.TRANSFER_ENCODING) || headers.containsKey(HttpHeaders.CONTENT_LENGTH)) {
                    messageBody = messageBody.doOnNext(data -> headers.setContentLength(data.readableByteCount()));
                }
                return getDelegate().writeWith(messageBody);
            }));
        }

        @Override
        public Mono<Void> writeAndFlushWith(Publisher<? extends Publisher<? extends DataBuffer>> body) {
            return writeWith(Flux.from(body).flatMapSequential(p -> p));
        }
    }

    public static class Config {

    }
}
