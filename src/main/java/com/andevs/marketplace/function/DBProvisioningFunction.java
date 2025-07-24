package com.andevs.marketplace.function;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.services.sqladmin.SQLAdmin;
import com.google.api.services.sqladmin.model.Database;
import com.google.api.services.sqladmin.model.User;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.functions.CloudEventsFunction;
import com.google.cloud.secretmanager.v1.CreateSecretRequest;
import com.google.cloud.secretmanager.v1.ProjectName;
import com.google.cloud.secretmanager.v1.Secret;
import com.google.cloud.secretmanager.v1.SecretManagerServiceClient;
import com.google.cloud.secretmanager.v1.SecretName;
import com.google.cloud.secretmanager.v1.SecretPayload;
import com.google.cloud.secretmanager.v1.AddSecretVersionRequest;
import com.google.events.cloud.firestore.v1.DocumentEventData;
import com.google.gson.Gson;
import com.google.protobuf.ByteString;
import io.cloudevents.CloudEvent;

import java.util.UUID;

public class DBProvisioningFunction implements CloudEventsFunction {

    /**
     * Obtiene el PROJECT_ID intentando múltiples variables de entorno
     * Esto mejora la compatibilidad entre entornos locales y Cloud Functions
     */
    private static String getProjectId() {
        // Intentar GCP_PROJECT primero (configuración manual)
        String projectId = System.getenv("GCP_PROJECT");
        if (projectId != null && !projectId.trim().isEmpty()) {
            return projectId;
        }
        
        // Intentar GOOGLE_CLOUD_PROJECT (automático en Cloud Functions)
        projectId = System.getenv("GOOGLE_CLOUD_PROJECT");
        if (projectId != null && !projectId.trim().isEmpty()) {
            return projectId;
        }
        
        // Intentar GCLOUD_PROJECT (otra variante común)
        projectId = System.getenv("GCLOUD_PROJECT");
        if (projectId != null && !projectId.trim().isEmpty()) {
            return projectId;
        }
        
        return null; // Se manejará la validación en el constructor
    }

    private static final String PROJECT_ID = getProjectId();
    private static final String INSTANCE_ID = "innovatex-marketplace-master";
    private static final String REGION = "us-east1"; // Región de la instancia SQL

    private final SQLAdmin sqlAdmin;
    private final SecretManagerServiceClient secretClient;
    private final Gson gson = new Gson();

    public DBProvisioningFunction() throws Exception {
        // Log de inicialización
        System.out.println("=== INICIALIZANDO DBProvisioningFunction ===");
        System.out.println("PROJECT_ID desde variable de entorno: " + PROJECT_ID);
        System.out.println("INSTANCE_ID: " + INSTANCE_ID);
        System.out.println("REGION: " + REGION);
        
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
        
        // Inicializa el cliente de Secret Manager
        this.secretClient = SecretManagerServiceClient.create();
        
        System.out.println("SQLAdmin y SecretManager clientes inicializados correctamente");
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
        
        if (password == null || password.trim().isEmpty()) {
            throw new IllegalArgumentException("password no puede ser null o vacío");
        }

        String dbName = clientName.toLowerCase().replaceAll("\\s+", "_");
        String userName = dbName + "_user";

        System.out.println("=== PARÁMETROS GENERADOS ===");
        System.out.println("DB Name: " + dbName);
        System.out.println("User Name: " + userName);
        System.out.println("Password: " + password.substring(0, Math.min(8, password.length())) + "...");

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

            System.out.println("=== CREANDO SECRETS EN SECRET MANAGER ===");
            // 4) Crear secrets con credenciales de conexión
            createConnectionSecrets(dbName, userName, password);

            System.out.printf("=== COMPLETADO ===\nCreada BD `%s` y usuario `%s` con secrets de conexión%n",
                    dbName, userName);
                    
        } catch (Exception e) {
            System.err.println("=== ERROR DURANTE LA CREACIÓN ===");
            System.err.println("Error type: " + e.getClass().getSimpleName());
            System.err.println("Error message: " + e.getMessage());
            System.err.println("Stack trace:");
            e.printStackTrace();
            throw e; // Re-lanza la excepción
        }
    }

    private void createConnectionSecrets(String dbName, String userName, String password) throws Exception {
        String secretPrefix = "marketplace-db-" + dbName;
        
        // Construir la cadena de conexión JDBC para MySQL en Cloud SQL
        String jdbcUrl = String.format(
            "jdbc:mysql://google/%s?cloudSqlInstance=%s:%s:%s&socketFactory=com.google.cloud.sql.mysql.SocketFactory&useSSL=false&allowPublicKeyRetrieval=true",
            dbName, PROJECT_ID, REGION, INSTANCE_ID
        );
        
        System.out.println("JDBC URL generada: " + jdbcUrl);
        
        // Crear secret para JDBC URL
        createSecret(secretPrefix + "-jdbc-url", jdbcUrl, "JDBC connection string for " + dbName);
        
        // Crear secret para username
        createSecret(secretPrefix + "-username", userName, "Database username for " + dbName);
        
        // Crear secret para password
        createSecret(secretPrefix + "-password", password, "Database password for " + dbName);
        
        // Crear secret combinado con toda la información en JSON
        String connectionInfo = gson.toJson(new ConnectionInfo(jdbcUrl, userName, password, dbName));
        createSecret(secretPrefix + "-connection-info", connectionInfo, "Complete connection info for " + dbName);
        
        System.out.println("Todos los secrets creados exitosamente para: " + dbName);
    }

    private void createSecret(String secretId, String secretValue, String description) throws Exception {
        try {
            System.out.println("Creando secret: " + secretId);
            
            ProjectName projectName = ProjectName.of(PROJECT_ID);
            
            // Crear el secret
            Secret secret = Secret.newBuilder()
                    .putLabels("client-type", "marketplace")
                    .putLabels("managed-by", "db-provisioning-function")
                    .build();
            
            CreateSecretRequest createSecretRequest = CreateSecretRequest.newBuilder()
                    .setParent(projectName.toString())
                    .setSecretId(secretId)
                    .setSecret(secret)
                    .build();
            
            Secret createdSecret = secretClient.createSecret(createSecretRequest);
            System.out.println("Secret creado: " + createdSecret.getName());
            
            // Agregar la primera versión del secret con el valor
            SecretPayload payload = SecretPayload.newBuilder()
                    .setData(ByteString.copyFromUtf8(secretValue))
                    .build();
            
            AddSecretVersionRequest addVersionRequest = AddSecretVersionRequest.newBuilder()
                    .setParent(createdSecret.getName())
                    .setPayload(payload)
                    .build();
            
            var version = secretClient.addSecretVersion(addVersionRequest);
            System.out.println("Version del secret creada: " + version.getName());
            
        } catch (Exception e) {
            System.err.println("Error creando secret " + secretId + ": " + e.getMessage());
            throw e;
        }
    }

    // Clase interna para el JSON de información de conexión
    private static class ConnectionInfo {
        public final String jdbcUrl;
        public final String username;
        public final String password;
        public final String databaseName;
        public final String instanceId;
        public final String projectId;
        public final String region;
        
        public ConnectionInfo(String jdbcUrl, String username, String password, String databaseName) {
            this.jdbcUrl = jdbcUrl;
            this.username = username;
            this.password = password;
            this.databaseName = databaseName;
            this.instanceId = INSTANCE_ID;
            this.projectId = PROJECT_ID;
            this.region = REGION;
        }
    }
}

