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
        // Log de inicialización
        System.out.println("=== INICIALIZANDO DBProvisioningFunction ===");
        System.out.println("PROJECT_ID desde variable de entorno: " + PROJECT_ID);
        System.out.println("INSTANCE_ID: " + INSTANCE_ID);
        
        // Validación temprana de PROJECT_ID
        if (PROJECT_ID == null || PROJECT_ID.trim().isEmpty()) {
            throw new IllegalStateException("La variable de entorno GCP_PROJECT no está configurada o está vacía");
        }
        
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
        
        System.out.println("SQLAdmin cliente inicializado correctamente");
    }

    @Override
    public void accept(CloudEvent event) throws Exception {
        var gson = new Gson();

        System.out.println("=== PROCESANDO NUEVO EVENTO ===");
        System.out.println("Event ID: " + event.getId());
        System.out.println("Event Type: " + event.getType());
        System.out.println("Event Source: " + event.getSource());
        
        DocumentEventData firestoreEventData = DocumentEventData.parseFrom(event.getData().toBytes());
        var document = firestoreEventData.getValue();
        System.out.println("Document: " + document);
        
        var documentData = document.getFieldsMap();
        System.out.println("Document fields count: " + documentData.size());
        
        String clientName = documentData.get("clientName").getStringValue();
        System.out.println("Client Name extraído: " + clientName);

        String password = documentData.get("password").getStringValue();
        System.out.println("password extraído: " + password);


        // Validaciones antes de proceder
        System.out.println("=== VALIDANDO PARÁMETROS ===");
        System.out.println("PROJECT_ID: " + PROJECT_ID);
        System.out.println("INSTANCE_ID: " + INSTANCE_ID);
        System.out.println("Client Name: " + clientName);
        
        if (clientName == null || clientName.trim().isEmpty()) {
            throw new IllegalArgumentException("clientName no puede ser null o vacío");
        }

        String dbName = clientName.toLowerCase().replaceAll("\\s+", "_");
        String userName = dbName + "_user";

        System.out.println("=== PARÁMETROS GENERADOS ===");
        System.out.println("DB Name: " + dbName);
        System.out.println("User Name: " + userName);
        System.out.println("Password: " + password.substring(0, 8) + "...");

        try {
            System.out.println("=== CREANDO BASE DE DATOS ===");
            // 2) Crea la base de datos
            Database db = new Database().setName(dbName);
            System.out.println("Database object creado, llamando a insert...");
            System.out.println("Parámetros para insert: PROJECT_ID=" + PROJECT_ID + ", INSTANCE_ID=" + INSTANCE_ID + ", dbName=" + dbName);
            
            sqlAdmin.databases()
                    .insert(PROJECT_ID, INSTANCE_ID, db)
                    .execute();
            
            System.out.println("Base de datos creada exitosamente: " + dbName);

            System.out.println("=== CREANDO USUARIO ===");
            // 3) Crea el usuario y asigna la contraseña
            User user = new User()
                    .setName(userName)
                    .setPassword(password);
            System.out.println("User object creado, llamando a insert...");
            
            sqlAdmin.users()
                    .insert(PROJECT_ID, INSTANCE_ID, user)
                    .execute();
            
            System.out.println("Usuario creado exitosamente: " + userName);

            System.out.printf("=== COMPLETADO ===\nCreada BD `%s` y usuario `%s` (pwd=%s)%n",
                    dbName, userName, password);
                    
        } catch (Exception e) {
            System.err.println("=== ERROR DURANTE LA CREACIÓN ===");
            System.err.println("Error type: " + e.getClass().getSimpleName());
            System.err.println("Error message: " + e.getMessage());
            System.err.println("Stack trace:");
            e.printStackTrace();
            throw e; // Re-lanza la excepción
        }
    }
}

