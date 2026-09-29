package com.zhuri.coding.file.config;
//
//@Configuration
//@ConditionalOnProperty(prefix = "spring.rabbitmq.listener.simple.retry",name = "enabled",havingValue = "true")
//public class ErrorConfiguration {
//
//    @Autowired
//    private IArticleClient articleClient;
//
//    @Bean
//    public DirectExchange errorExchange(){
//        return new DirectExchange("error.direct");
//    }
//
//    @Bean
//    public Queue errorQueue(){
//        return new Queue("error.queue");
//    }
//
//    @Bean
//    public Binding errorBinding(){
//        return BindingBuilder.bind(errorQueue()).to(errorExchange()).with("error");
//    }
//
//}
