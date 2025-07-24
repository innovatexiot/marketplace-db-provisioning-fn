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
import com.google.cloud.secretmanager.v1.Replication;
import com.google.events.cloud.firestore.v1.DocumentEventData;
import com.google.gson.Gson;
import com.google.protobuf.ByteString;
import io.cloudevents.CloudEvent;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.logging.Logger;

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
    private static final Logger logger = Logger.getLogger(DBProvisioningFunction.class.getName());

    public DBProvisioningFunction() throws Exception {
        // Log de inicialización
        logger.info("Inicializando DBProvisioningFunction con PROJECT_ID: " + PROJECT_ID + 
                   ", INSTANCE_ID: " + INSTANCE_ID + ", REGION: " + REGION);
        
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
        
        logger.info("✅ SQLAdmin y SecretManager clientes inicializados correctamente");
    }

    @Override
    public void accept(CloudEvent event) throws Exception {
        logger.info("=== PROCESANDO NUEVO EVENTO === Event ID: " + event.getId() + 
                   ", Type: " + event.getType() + ", Source: " + event.getSource());

        // Best Practice: Validar edad del evento para evitar reintentos infinitos
        if (isEventTooOld(event)) {
            logger.warning("Evento demasiado antiguo, descartando para evitar reintentos infinitos. Event ID: " + event.getId());
            return;
        }

        try {
            DocumentEventData firestoreEventData = DocumentEventData.parseFrom(event.getData().toBytes());
            var document = firestoreEventData.getValue();
            var documentData = document.getFieldsMap();
            
            logger.info("Documento de Firestore recibido con " + documentData.size() + " campos");
            
            // Extraer y validar datos del documento
            String clientName = documentData.get("clientName").getStringValue();
            String password = documentData.get("password").getStringValue();
            
            logger.info("Cliente extraído: " + clientName);

            // Validaciones
            validateRequiredParameters(clientName, password);

            // Generar parámetros
            String dbName = clientName.toLowerCase().replaceAll("\\s+", "_");
            String userName = dbName + "_user";

            logger.info("Parámetros generados - Database: " + dbName + ", User: " + userName);

            // Ejecutar aprovisionamiento paso a paso
            executeProvisioningSteps(dbName, userName, password);

            logger.info("=== PROVISIONING COMPLETADO EXITOSAMENTE para cliente: " + clientName + " ===");

        } catch (Exception e) {
            logger.severe("=== ERROR DURANTE LA CREACIÓN === " + e.getMessage());
            
            // Best Practice: No relanzar la excepción si el evento es muy antiguo
            if (isEventTooOld(event)) {
                logger.warning("Evento antiguo con error, no reintentando. Event ID: " + event.getId());
                return;
            }
            throw e;
        }
    }

    /**
     * Best Practice: Validar edad del evento para evitar reintentos infinitos
     * Basado en: https://cloud.google.com/functions/docs/bestpractices/retries
     */
    private boolean isEventTooOld(CloudEvent event) {
        if (event.getTime() == null) {
            return false;
        }
        
        OffsetDateTime eventTime = event.getTime();
        OffsetDateTime now = OffsetDateTime.now();
        Duration eventAge = Duration.between(eventTime, now);
        
        // Eventos mayores a 10 minutos se consideran muy antiguos
        boolean isTooOld = eventAge.compareTo(Duration.ofMinutes(10)) > 0;
        
        if (isTooOld) {
            logger.warning("Evento con antigüedad de " + eventAge.getSeconds() + " segundos es demasiado antiguo");
        }
        
        return isTooOld;
    }

    /**
     * Validar parámetros requeridos según documentación oficial
     */
    private void validateRequiredParameters(String clientName, String password) {
        if (PROJECT_ID == null || PROJECT_ID.trim().isEmpty()) {
            throw new IllegalStateException("PROJECT_ID es requerido pero está vacío o null");
        }
        if (INSTANCE_ID == null || INSTANCE_ID.trim().isEmpty()) {
            throw new IllegalStateException("INSTANCE_ID es requerido pero está vacío o null");
        }
        if (clientName == null || clientName.trim().isEmpty()) {
            throw new IllegalArgumentException("clientName no puede ser null o vacío");
        }
        if (password == null || password.trim().isEmpty()) {
            throw new IllegalArgumentException("password no puede ser null o vacío");
        }
        
        logger.info("Parámetros validados - PROJECT_ID: " + PROJECT_ID + ", INSTANCE_ID: " + INSTANCE_ID);
    }

    /**
     * Ejecutar pasos de aprovisionamiento con manejo individual de errores
     */
    private void executeProvisioningSteps(String dbName, String userName, String password) throws Exception {
        try {
            // Paso 1: Crear base de datos
            logger.info("Creando base de datos: " + dbName);
            Database db = new Database().setName(dbName);
            sqlAdmin.databases().insert(PROJECT_ID, INSTANCE_ID, db).execute();
            logger.info("✅ Base de datos creada exitosamente: " + dbName);

            // Paso 2: Crear usuario  
            logger.info("Creando usuario: " + userName);
            User user = new User().setName(userName).setPassword(password);
            sqlAdmin.users().insert(PROJECT_ID, INSTANCE_ID, user).execute();
            logger.info("✅ Usuario creado exitosamente: " + userName);

            // Paso 3: Crear secrets
            logger.info("Creando secrets para base de datos: " + dbName);
            createConnectionSecrets(dbName, userName, password);
            logger.info("✅ Secrets creados exitosamente para: " + dbName);

        } catch (Exception e) {
            logger.severe("Error en paso de aprovisionamiento: " + e.getMessage());
            throw e;
        }
    }

    private void createConnectionSecrets(String dbName, String userName, String password) throws Exception {
        String secretPrefix = "marketplace-db-" + dbName;
        
        // Construir la cadena de conexión JDBC para MySQL en Cloud SQL
        String jdbcUrl = String.format(
            "jdbc:mysql://google/%s?cloudSqlInstance=%s:%s:%s&socketFactory=com.google.cloud.sql.mysql.SocketFactory&useSSL=false&allowPublicKeyRetrieval=true",
            dbName, PROJECT_ID, REGION, INSTANCE_ID
        );
        
        logger.info("JDBC URL generada: " + jdbcUrl);
        
        // Crear secret para JDBC URL
        createSecret(secretPrefix + "-jdbc-url", jdbcUrl, "JDBC connection string for " + dbName);
        
        // Crear secret para username
        createSecret(secretPrefix + "-username", userName, "Database username for " + dbName);
        
        // Crear secret para password
        createSecret(secretPrefix + "-password", password, "Database password for " + dbName);
        
        // Crear secret combinado con toda la información en JSON
        String connectionInfo = gson.toJson(new ConnectionInfo(jdbcUrl, userName, password, dbName));
        createSecret(secretPrefix + "-connection-info", connectionInfo, "Complete connection info for " + dbName);
        
        logger.info("✅ Todos los secrets creados exitosamente para: " + dbName);
    }

    private void createSecret(String secretId, String secretValue, String description) throws Exception {
        try {
            logger.info("Creando secret: " + secretId);
            
            ProjectName projectName = ProjectName.of(PROJECT_ID);
            
            // Crear el secret con configuración de replicación
            Secret secret = Secret.newBuilder()
                    .putLabels("client-type", "marketplace")
                    .putLabels("managed-by", "db-provisioning-function")
                    .setReplication(Replication.newBuilder()
                            .setAutomatic(Replication.Automatic.newBuilder().build())
                            .build())
                    .build();
            
            CreateSecretRequest createSecretRequest = CreateSecretRequest.newBuilder()
                    .setParent(projectName.toString())
                    .setSecretId(secretId)
                    .setSecret(secret)
                    .build();
            
            Secret createdSecret = secretClient.createSecret(createSecretRequest);
            logger.info("✅ Secret creado: " + createdSecret.getName());
            
            // Agregar la primera versión del secret con el valor
            SecretPayload payload = SecretPayload.newBuilder()
                    .setData(ByteString.copyFromUtf8(secretValue))
                    .build();
            
            AddSecretVersionRequest addVersionRequest = AddSecretVersionRequest.newBuilder()
                    .setParent(createdSecret.getName())
                    .setPayload(payload)
                    .build();
            
            var version = secretClient.addSecretVersion(addVersionRequest);
            logger.info("✅ Version del secret creada: " + version.getName());
            
        } catch (Exception e) {
            logger.severe("❌ Error creando secret " + secretId + ": " + e.getMessage());
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

