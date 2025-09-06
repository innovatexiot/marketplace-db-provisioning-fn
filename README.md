# 🏢 InnovateX Marketplace - Schema Provisioning Function

## 📌 Descripción General
Esta es una **Google Cloud Function** en Java que automatiza el **aprovisionamiento de esquemas de base de datos** para clientes del marketplace InnovateX. La función:

- ✅ **Crea automáticamente** esquemas individuales para cada cliente en la BD `andevs_schemes`
- ✅ **Genera usuarios cliente** con acceso exclusivo a su esquema específico
- ✅ **Mantiene usuario root** con acceso completo a todos los esquemas
- ✅ **Almacena credenciales** de forma segura en Google Secret Manager
- ✅ **Se activa automáticamente** cuando se crea un documento en Firestore
- ✅ **Proporciona logs detallados** para depuración y auditoría

## 🏗 Arquitectura del Sistema

```
📄 Documento Firestore Creado
    ↓ (Trigger automático)
⚡ Cloud Function (SchemaProvisioningFunction)
    ↓ (Ejecuta 4 operaciones)
🗄️ Cloud SQL: Verificar BD andevs_schemes
👤 Cloud SQL: Crear Usuario Cliente
🏗️ Cloud SQL: Crear Esquema + Permisos
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

## 🚀 Proceso de Aprovisionamiento de Esquemas

### 1. **Verificación/Creación de Base de Datos Principal**
- **Nombre**: `andevs_schemes` (base de datos que contiene todos los esquemas)
- **Instancia**: `innovatex-marketplace-master`
- **Tipo**: MySQL en Cloud SQL
- **Comportamiento**: Se verifica existencia, se crea solo si no existe

### 2. **Creación de Usuario Cliente**
- **Nombre**: `mi_empresa_abc_user`
- **Contraseña**: La proporcionada en el documento
- **Permisos**: Acceso limitado solo a su esquema específico
- **Comportamiento**: Se actualiza si ya existe

### 3. **Creación Automática de Esquema y Permisos**
- **Esquema**: `mi_empresa_abc` (dentro de `andevs_schemes`)
- **Permisos Cliente**: SELECT, INSERT, UPDATE, DELETE, CREATE, DROP, INDEX, ALTER sobre su esquema
- **Permisos Root**: ALL PRIVILEGES sobre todos los esquemas
- **Ejecución**: ✅ **AUTOMÁTICA** usando conexión JDBC directa

### 4. **Almacenamiento de Secrets**
Para cada cliente se crean **4 secrets** en Secret Manager:

| Secret ID | Contenido | Ejemplo |
|-----------|-----------|---------|
| `marketplace-schema-{cliente}-jdbc-url` | Cadena de conexión JDBC | `jdbc:mysql://google/andevs_schemes?cloudSqlInstance=...` |
| `marketplace-schema-{cliente}-username` | Nombre de usuario | `mi_empresa_abc_user` |
| `marketplace-schema-{cliente}-password` | Contraseña | `mi-password-seguro-123` |
| `marketplace-schema-{cliente}-connection-info` | JSON completo | `{"jdbcUrl":"...", "username":"...", "schemaName":"mi_empresa_abc", ...}` |

## 📋 Prerequisitos

- **Java 17+** (`java -version`)
- **Maven 3.6+** (`mvn -version`)
- **Google Cloud SDK** (`gcloud version`)
- **Proyecto GCP configurado** (`gcloud init`)

### Infraestructura Requerida
- **Cloud SQL MySQL Instance**: `innovatex-marketplace-master`
- **Base de Datos Principal**: `andevs_schemes` (se crea automáticamente si no existe)
- **Secret Manager**: `mysql-root-password` (contraseña del usuario root de MySQL)
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

### Ejemplo Completo para Esquemas MySQL
```java
// Ejemplo de uso del secret generado
String connectionInfo = secretClient.accessSecretVersion(
    "projects/PROJECT_ID/secrets/marketplace-schema-mi_empresa-connection-info/versions/latest"
).getPayload().getData().toStringUtf8();

// JSON resultante:
{
  "jdbcUrl": "jdbc:mysql://google/andevs_schemes?cloudSqlInstance=PROJECT:us-east1:innovatex-marketplace-master&socketFactory=com.google.cloud.sql.mysql.SocketFactory&useSSL=false&allowPublicKeyRetrieval=true",
  "username": "mi_empresa_user", 
  "password": "password-seguro-123",
  "schemaName": "mi_empresa",
  "databaseName": "andevs_schemes",
  "instanceId": "innovatex-marketplace-master",
  "projectId": "PROJECT_ID",
  "region": "us-east1"
}

// Para usar el esquema específico en consultas:
// USE mi_empresa; o prefijar tablas: SELECT * FROM mi_empresa.mi_tabla;
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
1. **Cloud SQL**: Verificar que existe la BD `andevs_schemes` y el esquema `empresa_de_prueba`
2. **Usuarios**: Verificar que se creó el usuario `empresa_de_prueba_user`
3. **Secret Manager**: Verificar que se crearon los 4 secrets con prefijo `marketplace-schema-`
4. **Logs**: Revisar los logs en Cloud Functions para comandos SQL generados

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
=== VERIFICANDO BD ANDEVS_SCHEMES ===
=== CREANDO USUARIO CLIENTE ===
=== GENERANDO COMANDOS SQL PARA ESQUEMA ===
=== CREANDO SECRETS EN SECRET MANAGER ===
=== COMPLETADO ===
```

## 🔐 Recuperar Credenciales

### Usando gcloud CLI
```bash
# Obtener JDBC URL
gcloud secrets versions access latest --secret="marketplace-schema-empresa_prueba-jdbc-url"

# Obtener username
gcloud secrets versions access latest --secret="marketplace-schema-empresa_prueba-username"

# Obtener password
gcloud secrets versions access latest --secret="marketplace-schema-empresa_prueba-password"

# Obtener JSON completo
gcloud secrets versions access latest --secret="marketplace-schema-empresa_prueba-connection-info"
```

### Usando SDK (ejemplo en Java)
```java
SecretManagerServiceClient client = SecretManagerServiceClient.create();
String secretName = "projects/PROJECT_ID/secrets/marketplace-schema-cliente-jdbc-url/versions/latest";
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

## ✅ Aprovisionamiento Completamente Automatizado

**¡NUEVO!** La función ahora ejecuta automáticamente todos los comandos SQL necesarios usando una **conexión JDBC directa**.

### Configuración Inicial Requerida:

**Opción 1: Script Automático (Recomendado)**
```bash
# Ejecutar script de configuración automática
./setup-automation.sh
```

**Opción 2: Configuración Manual**

1. **Crear secret para contraseña de root**:
```bash
# Crear el secret con la contraseña del usuario root de MySQL
gcloud secrets create mysql-root-password --data-file=- <<< "tu-password-root-mysql"
```

2. **Otorgar permisos** a la service account de la Cloud Function:
```bash
# Permiso para leer el secret de root
gcloud secrets add-iam-policy-binding mysql-root-password \
  --member="serviceAccount:PROJECT_ID@appspot.gserviceaccount.com" \
  --role="roles/secretmanager.secretAccessor"
```

### Proceso Automatizado:
1. ✅ **Verificar/Crear BD** `andevs_schemes`
2. ✅ **Crear/Actualizar usuario** cliente
3. ✅ **Ejecutar SQL automáticamente**:
   - `CREATE SCHEMA IF NOT EXISTS cliente_schema`
   - `GRANT permisos específicos TO usuario_cliente`
   - `GRANT ALL PRIVILEGES TO root`
   - `FLUSH PRIVILEGES`
4. ✅ **Crear secrets** en Secret Manager

### Comandos SQL Ejecutados Automáticamente:
```sql
-- Se ejecutan automáticamente por la Cloud Function
CREATE SCHEMA IF NOT EXISTS `mi_empresa_abc`;
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, DROP, INDEX, ALTER 
ON `mi_empresa_abc`.* TO 'mi_empresa_abc_user'@'%';
GRANT ALL PRIVILEGES ON `mi_empresa_abc`.* TO 'root'@'%';
FLUSH PRIVILEGES;
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
- **MySQL Connector/J**: Conexión JDBC directa a Cloud SQL
- **Cloud SQL MySQL Socket Factory**: Conexiones seguras a Cloud SQL
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



