package com.zhongbo.mindos.assistant.api.news;

interface NewsDeliveryClient {

    boolean deliver(String message, NewsPushConfig config);
}
