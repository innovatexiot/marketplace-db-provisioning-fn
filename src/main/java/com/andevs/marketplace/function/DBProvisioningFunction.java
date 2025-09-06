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

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
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
    private static final String SCHEMAS_DATABASE = "andevs_schemes"; // Base de datos que contendrá todos los esquemas

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

            // Generar parámetros para esquema
            String schemaName = clientName.toLowerCase().replaceAll("\\s+", "_");
            String userName = schemaName + "_user";

            logger.info("Parámetros generados - Schema: " + schemaName + ", User: " + userName);

            // Ejecutar aprovisionamiento de esquema paso a paso
            executeSchemaProvisioningSteps(schemaName, userName, password);

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
     * Ejecutar pasos de aprovisionamiento de esquema con manejo individual de errores
     */
    private void executeSchemaProvisioningSteps(String schemaName, String userName, String password) throws Exception {
        try {
            // Paso 1: Verificar que la base de datos andevs_schemes existe
            logger.info("Verificando existencia de base de datos: " + SCHEMAS_DATABASE);
            ensureSchemasDatabaseExists();
            
            // Paso 2: Crear usuario cliente
            logger.info("Creando usuario cliente: " + userName);
            createClientUser(userName, password);
            logger.info("✅ Usuario cliente creado exitosamente: " + userName);

            // Paso 3: Crear esquema y otorgar permisos específicos
            logger.info("Creando esquema: " + schemaName);
            createSchemaWithPermissions(schemaName, userName);
            logger.info("✅ Esquema creado exitosamente: " + schemaName);

            // Paso 4: Crear secrets para el esquema
            logger.info("Creando secrets para esquema: " + schemaName);
            createSchemaConnectionSecrets(schemaName, userName, password);
            logger.info("✅ Secrets creados exitosamente para: " + schemaName);

        } catch (Exception e) {
            logger.severe("Error en paso de aprovisionamiento de esquema: " + e.getMessage());
            throw e;
        }
    }

    /**
     * Verifica que la base de datos andevs_schemes existe, si no, la crea
     */
    private void ensureSchemasDatabaseExists() throws Exception {
        try {
            // Intentar obtener la base de datos
            sqlAdmin.databases().get(PROJECT_ID, INSTANCE_ID, SCHEMAS_DATABASE).execute();
            logger.info("✅ Base de datos " + SCHEMAS_DATABASE + " ya existe");
        } catch (Exception e) {
            if (e.getMessage().contains("404") || e.getMessage().contains("not found")) {
                logger.info("Base de datos " + SCHEMAS_DATABASE + " no existe, creándola...");
                Database db = new Database().setName(SCHEMAS_DATABASE);
                sqlAdmin.databases().insert(PROJECT_ID, INSTANCE_ID, db).execute();
                logger.info("✅ Base de datos " + SCHEMAS_DATABASE + " creada exitosamente");
            } else {
                throw e;
            }
        }
    }

    /**
     * Crear usuario cliente con permisos limitados
     */
    private void createClientUser(String userName, String password) throws Exception {
        try {
            // Verificar si el usuario ya existe
            try {
                sqlAdmin.users().get(PROJECT_ID, INSTANCE_ID, userName).execute();
                logger.info("Usuario " + userName + " ya existe, actualizando contraseña...");
                
                // Si existe, actualizar la contraseña
                User existingUser = new User().setName(userName).setPassword(password);
                sqlAdmin.users().update(PROJECT_ID, INSTANCE_ID, existingUser).execute();
                logger.info("✅ Contraseña del usuario " + userName + " actualizada");
                
            } catch (Exception e) {
                if (e.getMessage().contains("404") || e.getMessage().contains("not found")) {
                    // Usuario no existe, crearlo
                    logger.info("Creando nuevo usuario: " + userName);
                    User user = new User().setName(userName).setPassword(password);
                    sqlAdmin.users().insert(PROJECT_ID, INSTANCE_ID, user).execute();
                    logger.info("✅ Usuario " + userName + " creado exitosamente");
                } else {
                    throw e;
                }
            }
        } catch (Exception e) {
            logger.severe("Error creando/actualizando usuario " + userName + ": " + e.getMessage());
            throw e;
        }
    }

    /**
     * Crear esquema y otorgar permisos específicos al usuario usando conexión JDBC directa
     */
    private void createSchemaWithPermissions(String schemaName, String userName) throws Exception {
        logger.info("Creando esquema " + schemaName + " y otorgando permisos a " + userName);
        
        // Construir JDBC URL para conexión como root
        String jdbcUrl = String.format(
            "jdbc:mysql://google/%s?cloudSqlInstance=%s:%s:%s&socketFactory=com.google.cloud.sql.mysql.SocketFactory&useSSL=false&allowPublicKeyRetrieval=true",
            SCHEMAS_DATABASE, PROJECT_ID, REGION, INSTANCE_ID
        );
        
        try (Connection connection = DriverManager.getConnection(jdbcUrl, "root", getRootPassword())) {
            logger.info("✅ Conexión JDBC establecida como usuario root");
            
            try (Statement statement = connection.createStatement()) {
                // 1. Crear esquema
                String createSchemaSQL = String.format("CREATE SCHEMA IF NOT EXISTS `%s`", schemaName);
                statement.executeUpdate(createSchemaSQL);
                logger.info("✅ Esquema creado: " + schemaName);
                
                // 2. Otorgar permisos específicos al usuario cliente
                String grantClientSQL = String.format(
                    "GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, DROP, INDEX, ALTER ON `%s`.* TO '%s'@'%%'",
                    schemaName, userName
                );
                statement.executeUpdate(grantClientSQL);
                logger.info("✅ Permisos otorgados a usuario cliente: " + userName);
                
                // 3. Asegurar que root tenga todos los permisos
                String grantRootSQL = String.format("GRANT ALL PRIVILEGES ON `%s`.* TO 'root'@'%%'", schemaName);
                statement.executeUpdate(grantRootSQL);
                logger.info("✅ Permisos completos otorgados a usuario root");
                
                // 4. Aplicar cambios
                statement.executeUpdate("FLUSH PRIVILEGES");
                logger.info("✅ Privilegios aplicados exitosamente");
                
            }
        } catch (Exception e) {
            logger.severe("❌ Error ejecutando comandos SQL: " + e.getMessage());
            throw e;
        }
        
        logger.info("✅ Esquema " + schemaName + " creado y configurado automáticamente");
    }

    /**
     * Obtiene la contraseña del usuario root desde Secret Manager
     */
    private String getRootPassword() throws Exception {
        try {
            String secretName = String.format("projects/%s/secrets/mysql-root-password/versions/latest", PROJECT_ID);
            String rootPassword = secretClient.accessSecretVersion(secretName)
                .getPayload().getData().toStringUtf8();
            logger.info("✅ Contraseña de root obtenida desde Secret Manager");
            return rootPassword;
        } catch (Exception e) {
            logger.severe("❌ Error obteniendo contraseña de root: " + e.getMessage());
            logger.info("💡 Asegúrate de que existe el secret 'mysql-root-password' en Secret Manager");
            throw e;
        }
    }

    /**
     * Crear secrets para conexión al esquema específico
     */
    private void createSchemaConnectionSecrets(String schemaName, String userName, String password) throws Exception {
        String secretPrefix = "marketplace-schema-" + schemaName;
        
        // Crear JDBC URL específica para el esquema
        String jdbcUrl = String.format(
            "jdbc:mysql://google/%s?cloudSqlInstance=%s:%s:%s&socketFactory=com.google.cloud.sql.mysql.SocketFactory&useSSL=false&allowPublicKeyRetrieval=true",
            SCHEMAS_DATABASE, PROJECT_ID, REGION, INSTANCE_ID
        );
        
        // Crear secrets individuales
        createSecret(secretPrefix + "-jdbc-url", jdbcUrl, "JDBC URL for schema " + schemaName);
        createSecret(secretPrefix + "-username", userName, "Username for schema " + schemaName);
        createSecret(secretPrefix + "-password", password, "Password for schema " + schemaName);
        
        // Crear secret con toda la información de conexión
        SchemaConnectionInfo connectionInfo = new SchemaConnectionInfo(jdbcUrl, userName, password, schemaName);
        String connectionInfoJson = gson.toJson(connectionInfo);
        createSecret(secretPrefix + "-connection-info", connectionInfoJson, "Complete connection info for schema " + schemaName);
        
        logger.info("✅ Todos los secrets creados exitosamente para esquema: " + schemaName);
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

    // Clase interna para el JSON de información de conexión de esquemas
    private static class SchemaConnectionInfo {
        public final String jdbcUrl;
        public final String username;
        public final String password;
        public final String schemaName;
        public final String databaseName;
        public final String instanceId;
        public final String projectId;
        public final String region;
        
        public SchemaConnectionInfo(String jdbcUrl, String username, String password, String schemaName) {
            this.jdbcUrl = jdbcUrl;
            this.username = username;
            this.password = password;
            this.schemaName = schemaName;
            this.databaseName = SCHEMAS_DATABASE;
            this.instanceId = INSTANCE_ID;
            this.projectId = PROJECT_ID;
            this.region = REGION;
        }
    }
}

