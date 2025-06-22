package com.andevs.marketplace.function;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.services.sqladmin.SQLAdmin;
import com.google.api.services.sqladmin.model.Database;
import com.google.api.services.sqladmin.model.User;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.functions.CloudEventsFunction;
import com.google.events.cloud.firestore.v1.DocumentEventData;
import com.google.gson.Gson;
import io.cloudevents.CloudEvent;

import java.util.UUID;

public class DBProvisioningFunction implements CloudEventsFunction {


    private static final String PROJECT_ID = System.getenv("GCP_PROJECT");
    private static final String INSTANCE_ID = "innovatex-marketplace-master"; // ej: "marketplace-sql"


    private final SQLAdmin sqlAdmin;


    public DBProvisioningFunction() throws Exception {
        // Construye el cliente SQL Admin usando ADC
        GoogleCredentials creds = GoogleCredentials.getApplicationDefault()
                .createScoped("https://www.googleapis.com/auth/cloud-platform");
        this.sqlAdmin = new SQLAdmin.Builder(
                GoogleNetHttpTransport.newTrustedTransport(),
                JacksonFactory.getDefaultInstance(),
                new HttpCredentialsAdapter(creds)
        )
                .setApplicationName("db-provisioning")
                .build();
    }


    @Override
    public void accept(CloudEvent event) throws Exception {
        var gson = new Gson();

        System.out.println("New event received: " + event);
        DocumentEventData firestoreEventData = DocumentEventData.parseFrom(event.getData().toBytes());
        var document = firestoreEventData.getValue();
        System.out.println("Document: " + document);
        var documentData = document.getFieldsMap();
        String clientName = documentData.get("clientName").getStringValue();
        System.out.println("Client Name: " + clientName);


        String dbName = clientName.toLowerCase().replaceAll("\\s+", "_");
        String userName = dbName + "_user";
        String password = UUID.randomUUID().toString();  // contraseña aleatoria

        // 2) Crea la base de datos
        Database db = new Database().setName(dbName);
        sqlAdmin.databases()
                .insert(PROJECT_ID, INSTANCE_ID, db)
                .execute();

        // 3) Crea el usuario y asigna la contraseña
        User user = new User()
                .setName(userName)
                .setPassword(password);
        sqlAdmin.users()
                .insert(PROJECT_ID, INSTANCE_ID, user)
                .execute();

        System.out.printf("Creada BD `%s` y usuario `%s` (pwd=%s)%n",
                dbName, userName, password);

    }
}

