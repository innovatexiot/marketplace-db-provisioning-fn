package com.andevs.marketplace.function;

import com.andevs.marketplace.function.model.ProvisioningInput;
import com.google.cloud.functions.CloudEventsFunction;
import com.google.events.cloud.firestore.v1.DocumentEventData;
import com.google.gson.Gson;
import io.cloudevents.CloudEvent;

public class DBProvisioningFunction implements CloudEventsFunction {

    @Override
    public void accept(CloudEvent event) throws Exception {
        var gson = new Gson();

        System.out.println("New event received: " + event);
        DocumentEventData firestoreEventData = DocumentEventData.parseFrom(event.getData().toBytes());
        var document = firestoreEventData.getValue();
        var documentData = document.getFieldsMap();
        var input = gson.fromJson(gson.toJson(documentData), ProvisioningInput.class);
        System.out.println("Input: " + input);

    }
}

