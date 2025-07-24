# 🏢 InnovateX Marketplace - DB Provisioning Function

## 📌 Descripción General
Esta es una **Google Cloud Function** en Java que automatiza el **aprovisionamiento de bases de datos** para clientes del marketplace InnovateX. La función:

- ✅ **Crea automáticamente** bases de datos individuales para cada cliente
- ✅ **Genera usuarios** con acceso exclusivo a su base de datos
- ✅ **Almacena credenciales** de forma segura en Google Secret Manager
- ✅ **Se activa automáticamente** cuando se crea un documento en Firestore
- ✅ **Proporciona logs detallados** para depuración y auditoría

## 🏗 Arquitectura del Sistema

```
📄 Documento Firestore Creado
    ↓ (Trigger automático)
⚡ Cloud Function (DBProvisioningFunction)
    ↓ (Ejecuta 3 operaciones)
🗄️ Cloud SQL: Crear BD + Usuario
🔐 Secret Manager: Almacenar credenciales
📝 Logs: Registro detallado
```

## 🔧 Configuración del Trigger

**Database**: `db-marketplace`  
**Colección**: `db-provisioning-requests/{docId}`  
**Evento**: `google.cloud.firestore.document.v1.created`  
**Región**: `us-east1`

## 📝 Formato de Entrada (Documento Firestore)

```json
{
  "clientName": "Mi Empresa ABC",
  "password": "mi-password-seguro-123"
}
```

## 🚀 Proceso de Aprovisionamiento

### 1. **Creación de Base de Datos**
- **Nombre**: `mi_empresa_abc` (convierte espacios a guiones bajos)
- **Instancia**: `innovatex-marketplace-master`
- **Tipo**: MySQL en Cloud SQL

### 2. **Creación de Usuario**
- **Nombre**: `mi_empresa_abc_user`
- **Contraseña**: La proporcionada en el documento
- **Permisos**: Acceso completo a su base de datos

### 3. **Almacenamiento de Secrets**
Para cada cliente se crean **4 secrets** en Secret Manager:

| Secret ID | Contenido | Ejemplo |
|-----------|-----------|---------|
| `marketplace-db-{cliente}-jdbc-url` | Cadena de conexión JDBC | `jdbc:mysql://google/mi_empresa_abc?cloudSqlInstance=...` |
| `marketplace-db-{cliente}-username` | Nombre de usuario | `mi_empresa_abc_user` |
| `marketplace-db-{cliente}-password` | Contraseña | `mi-password-seguro-123` |
| `marketplace-db-{cliente}-connection-info` | JSON completo | `{"jdbcUrl":"...", "username":"...", "password":"..."}` |

## 📋 Prerequisitos

- **Java 17+** (`java -version`)
- **Maven 3.6+** (`mvn -version`)
- **Google Cloud SDK** (`gcloud version`)
- **Proyecto GCP configurado** (`gcloud init`)

### Infraestructura Requerida
- **Cloud SQL MySQL Instance**: `innovatex-marketplace-master`
- **Firestore Database**: `db-marketplace`
- **Secret Manager**: Habilitado en el proyecto

### Permisos Requeridos
La función necesita los siguientes permisos en GCP:
- `cloudsql.instances.get`
- `cloudsql.databases.create`
- `cloudsql.users.create`
- `secretmanager.secrets.create`
- `secretmanager.versions.add`

## 🗄️ Configuración MySQL

### Cadena de Conexión Generada
```
jdbc:mysql://google/{database_name}?cloudSqlInstance={project}:{region}:{instance}&socketFactory=com.google.cloud.sql.mysql.SocketFactory&useSSL=false&allowPublicKeyRetrieval=true
```

### Ejemplo Completo para MySQL
```java
// Ejemplo de uso del secret generado
String connectionInfo = secretClient.accessSecretVersion(
    "projects/PROJECT_ID/secrets/marketplace-db-mi_empresa-connection-info/versions/latest"
).getPayload().getData().toStringUtf8();

// JSON resultante:
{
  "jdbcUrl": "jdbc:mysql://google/mi_empresa?cloudSqlInstance=PROJECT:us-east1:innovatex-marketplace-master&socketFactory=com.google.cloud.sql.mysql.SocketFactory&useSSL=false&allowPublicKeyRetrieval=true",
  "username": "mi_empresa_user", 
  "password": "password-seguro-123",
  "databaseName": "mi_empresa",
  "instanceId": "innovatex-marketplace-master",
  "projectId": "PROJECT_ID",
  "region": "us-east1"
}
```

## 🏃 Ejecución Local

### Compilar el proyecto
```bash
mvn clean package
```

### Ejecutar localmente
```bash
mvn function:run
```

### Configurar variables de entorno (desarrollo)
```bash
export GCP_PROJECT="tu-proyecto-id"
export GOOGLE_APPLICATION_CREDENTIALS="path/to/service-account.json"
```

## 🚀 Despliegue

### Usando Cloud Build (Recomendado)
```bash
gcloud builds submit --config cloudbuild.yaml
```

### Usando gcloud directamente
```bash
gcloud functions deploy marketplace-db-provisioning-fn \
  --gen2 \
  --region=us-east1 \
  --runtime=java17 \
  --source=. \
  --entry-point=com.andevs.marketplace.function.DBProvisioningFunction \
  --trigger-event-filters="type=google.cloud.firestore.document.v1.created" \
  --trigger-event-filters="database=db-marketplace" \
  --trigger-event-filters-path-pattern="document=db-provisioning-requests/{docId}" \
  --allow-unauthenticated
```

## 🧪 Pruebas

### Crear documento de prueba en Firestore
```javascript
// En la consola de Firebase o mediante SDK
db.collection('db-provisioning-requests').add({
  clientName: "Empresa de Prueba",
  password: "password-seguro-123"
});
```

### Verificar resultados
1. **Cloud SQL**: Verificar que se creó la BD `empresa_de_prueba`
2. **Secret Manager**: Verificar que se crearon los 4 secrets
3. **Logs**: Revisar los logs en Cloud Functions

## 📊 Monitoring y Logs

### Ver logs de la función
```bash
gcloud functions logs read marketplace-db-provisioning-fn --region=us-east1
```

### Estructura de logs
```
=== INICIALIZANDO DBProvisioningFunction ===
=== PROCESANDO NUEVO EVENTO ===
=== VALIDANDO PARÁMETROS ===
=== PARÁMETROS GENERADOS ===
=== CREANDO BASE DE DATOS ===
=== CREANDO USUARIO ===
=== CREANDO SECRETS EN SECRET MANAGER ===
=== COMPLETADO ===
```

## 🔐 Recuperar Credenciales

### Usando gcloud CLI
```bash
# Obtener JDBC URL
gcloud secrets versions access latest --secret="marketplace-db-empresa_prueba-jdbc-url"

# Obtener username
gcloud secrets versions access latest --secret="marketplace-db-empresa_prueba-username"

# Obtener password
gcloud secrets versions access latest --secret="marketplace-db-empresa_prueba-password"

# Obtener JSON completo
gcloud secrets versions access latest --secret="marketplace-db-empresa_prueba-connection-info"
```

### Usando SDK (ejemplo en Java)
```java
SecretManagerServiceClient client = SecretManagerServiceClient.create();
String secretName = "projects/PROJECT_ID/secrets/marketplace-db-cliente-jdbc-url/versions/latest";
String jdbcUrl = client.accessSecretVersion(secretName).getPayload().getData().toStringUtf8();
```

## 🛠 Configuración

### Variables de Entorno
- `GCP_PROJECT`: ID del proyecto de Google Cloud
- `GOOGLE_APPLICATION_CREDENTIALS`: Ruta al archivo de credenciales (desarrollo local)

### Constantes Configurables
```java
private static final String INSTANCE_ID = "innovatex-marketplace-master";
private static final String REGION = "us-east1";
```

## 🔧 Troubleshooting

### Error: "Required parameter project must be specified"
- Verificar que `GCP_PROJECT` esté configurado
- En Cloud Functions, puede usar `GOOGLE_CLOUD_PROJECT` automáticamente

### Error de permisos en Secret Manager
```bash
gcloud projects add-iam-policy-binding PROJECT_ID \
  --member="serviceAccount:function-sa@PROJECT_ID.iam.gserviceaccount.com" \
  --role="roles/secretmanager.admin"
```

### Error de conexión a Cloud SQL
- Verificar que la instancia `innovatex-marketplace-master` existe
- Confirmar que la región coincide con la configuración

## 📚 Dependencias Principales

- **Google Cloud Functions Framework**: Ejecución de funciones
- **Google Cloud SQL Admin API**: Gestión de bases de datos
- **Google Cloud Secret Manager**: Almacenamiento seguro de credenciales
- **Google Cloud Firestore Events**: Triggers de eventos
- **Gson**: Procesamiento JSON

## 🏷 Etiquetas de Secrets

Todos los secrets creados incluyen las siguientes etiquetas:
- `client-type: marketplace`
- `managed-by: db-provisioning-function`

## ⚠️ Consideraciones de Seguridad

1. **Contraseñas**: Se almacenan únicamente en Secret Manager
2. **Acceso**: Solo servicios autorizados pueden leer los secrets
3. **Auditoría**: Todos los accesos se registran en Cloud Audit Logs
4. **Rotación**: Los secrets pueden actualizarse sin afectar la función

---

## 👤 Mantenido por AnDevs Team
Marketplace InnovateX - Sistema de Aprovisionamiento Automático



