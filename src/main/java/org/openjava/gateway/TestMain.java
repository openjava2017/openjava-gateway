package org.openjava.gateway;

import reactor.core.publisher.Mono;

public class TestMain {
    public static void main(String[] args) throws Exception {
        Mono.just(100)
        .doOnNext(num -> System.out.println(num))//不需要返回值
        .doOnError(throwable -> System.out.println(throwable.getMessage()))//相当于catch异常
        .flatMap(num -> Mono.just(1 + num)) // 需要返回值
        .onErrorReturn(1) // 放出错时返回什么值
        .then(// 当上一个Mono执行完之后执行这里的Mono
            Mono.just("1000").doOnNext(str -> System.out.println(str + 1))
        ).doOnSuccess(i -> System.out.println("-----" + i))
        //.block() //阻塞执行当前mono，并且等待其执行完毕
        .subscribe(s -> System.out.println(s)); //异步执行当前Mono,和Thread的start方法有相似的功能
    }
}
