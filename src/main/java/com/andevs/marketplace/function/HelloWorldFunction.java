package com.andevs.marketplace.function;

import com.google.cloud.functions.CloudEventsFunction;
import com.google.events.cloud.firestore.v1.DocumentEventData;
import com.google.gson.Gson;
import io.cloudevents.CloudEvent;

public class HelloWorldFunction implements CloudEventsFunction {

    @Override
    public void accept(CloudEvent event) throws Exception {

        System.out.println("New event received: " + event);
        DocumentEventData firestoreEventData = DocumentEventData.parseFrom(event.getData().toBytes());
        System.out.println("firestoreEventData: " + firestoreEventData);
        System.out.println("Event type: " + event.getType());
        System.out.println("Event source: " + event.getSource());
        System.out.println("Event data: " + new String(event.getData().toBytes()));
        System.out.println("Event json: " + new Gson().toJson(event));
    }
}

