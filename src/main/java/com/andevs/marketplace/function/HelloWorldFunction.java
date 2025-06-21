package com.andevs.marketplace.function;

import com.google.cloud.functions.CloudEventsFunction;
import com.google.gson.Gson;
import io.cloudevents.CloudEvent;

public class HelloWorldFunction implements CloudEventsFunction {

    @Override
    public void accept(CloudEvent event) throws Exception {

        System.out.println("New event received: " + event);
        System.out.println("json: " + new Gson().toJson(event));
    }
}

