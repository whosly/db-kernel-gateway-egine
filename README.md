# Multi-Protocol Database Gateway Engine

This is a proxy gateway engine that implements multiple database protocols. It uses SQL parsing and is developed in Java 17.

## Overview

The Multi-Protocol Database Gateway Engine provides unified access to various database systems through different protocols. It acts as an intermediary layer that translates client requests from various protocols into native database queries and routes them to the appropriate backend databases.

## Key Features

- Full MySQL protocol implementation for client connections
- SQL parsing and translation using Alibaba Druid
- Database connection proxy based on user-provided authentication
- Support for multiple database backends (MySQL, PostgreSQL, Oracle, SQL Server)
- Security controls and monitoring capabilities
- Connection pooling and load balancing

## Documentation

- [README.md](README.md) - This file, containing quick start information
- [docs/COMPREHENSIVE_DOCUMENTATION.md](docs/COMPREHENSIVE_DOCUMENTATION.md) - Complete project documentation
- [docs/PROJECT_OBJECTIVES.md](docs/PROJECT_OBJECTIVES.md) - Project goals and objectives
- [docs/HIGH_LEVEL_DESIGN.md](docs/HIGH_LEVEL_DESIGN.md) - High-level architecture design
- [docs/DETAILED_DESIGN.md](docs/DETAILED_DESIGN.md) - Detailed technical design
- [docs/ENVIRONMENT_DOCUMENTATION.md](docs/ENVIRONMENT_DOCUMENTATION.md) - Environment setup guide

## Quick Start

### Prerequisites

- Java 17 (configured at C:\Users\parker\.jdks\azul-17.0.14)
- Maven 3.6+ (configured at D:\devlops\apache-maven-3.9.9)
- Target database systems (MySQL, PostgreSQL, etc.)

### Building and Running

```bash
# Build the project
D:\devlops\apache-maven-3.9.9\bin\mvn clean package

# Run the application
java -jar target/muti-protocol-gateway-egine-1.0.0-SNAPSHOT.jar
```

Or use the provided batch script:
```bash
start-gateway.bat
```

## Usage

### Configuration

Edit `src/main/resources/application.yml` to configure the gateway:

```yaml
gateway:
  # Database type: mysql or postgresql
  proxy-db-type: postgresql
  # Proxy port
  proxy-port: 5433

  # Target database configuration
  target:
    host: localhost
    port: 5432
    username: postgres
    password: your_password
    database: your_database
```

### Connecting to the Gateway

#### MySQL Gateway

```bash
# Connect using MySQL client
mysql -h localhost -P 3307 -u root -p

# Enter the target database password when prompted
```

#### PostgreSQL Gateway

```bash
# Connect using psql client
psql -h localhost -p 5433 -U postgres -d your_database

# Or using connection string
psql "postgresql://postgres:password@localhost:5433/your_database"
```

### Example Usage

#### MySQL Example

```bash
# Connect to MySQL gateway
mysql -h localhost -P 3307 -u root -p

# Execute queries
mysql> SHOW DATABASES;
mysql> USE demo;
mysql> SELECT * FROM users;
mysql> INSERT INTO users (name, age) VALUES ('Alice', 25);
```

#### PostgreSQL Example

```bash
# Connect to PostgreSQL gateway
psql -h localhost -p 5433 -U postgres -d dmp

# Execute queries
dmp=# \dt
dmp=# SELECT * FROM users;
dmp=# INSERT INTO users (name, age) VALUES ('Bob', 30);
dmp=# CREATE TABLE test (id SERIAL PRIMARY KEY, name VARCHAR(100));
```

## Protocol Implementations

### MySQL Protocol

The gateway implements a substantial portion of the MySQL client-server protocol, including:

#### Handshake Phase
- Server greeting packet with protocol version, server version, and connection ID
- Authentication packet parsing with capability flags and character set support
- Secure password scrambling using MySQL 4.1+ authentication method

#### Command Phase
- SQL statement execution with result set handling
- Column definition packets for metadata transmission
- Row data packets for result transmission
- OK and Error packet responses
- EOF packet signaling

#### Supported Features
- Text protocol result sets
- Basic data type mapping between MySQL and JDBC
- Connection state management
- Graceful connection termination

#### Limitations
- Prepared statements are not yet implemented
- SSL/TLS encryption is not yet supported
- Advanced MySQL protocol features like compression are not implemented
- Only basic authentication is supported

### PostgreSQL Protocol

The gateway implements the PostgreSQL wire protocol (version 3.0), including:

#### Startup Phase
- Startup message parsing with protocol version and parameters
- SSL request handling (currently rejected)
- Authentication flow (simplified, direct authentication)
- Parameter status messages
- Backend key data
- ReadyForQuery messages

#### Query Phase
- Simple Query protocol for direct SQL execution
- Extended Query protocol (Parse, Bind, Execute, Describe, Close, Sync)
- Result set handling with RowDescription and DataRow messages
- CommandComplete messages for DML/DDL operations
- Error response messages

#### Supported Features
- SELECT queries with full result set support
- INSERT, UPDATE, DELETE operations
- DDL operations (CREATE, DROP, ALTER)
- Multiple data types mapping (JDBC to PostgreSQL OID)
- NULL value handling
- Transaction status reporting

#### Limitations
- SSL/TLS encryption is not yet supported
- Authentication is simplified (password verification skipped)
- Extended query protocol is partially implemented
- Transaction management is basic
- Prepared statement caching is not implemented

## Project Structure

```
src/
├── main/
│   ├── java/
│   │   └── com.whosly.gateway/
│   │       ├── adapter/           # Protocol adapters for different database protocols
│   │       │   ├── mysql/         # MySQL protocol specific classes
│   │       │   │   ├── MySQLPacket.java       # MySQL packet handling
│   │       │   │   ├── MySQLHandshake.java    # MySQL handshake and authentication
│   │       │   │   └── MySQLResultSet.java    # MySQL result set handling
│   │       │   ├── postgresql/    # PostgreSQL protocol specific classes
│   │       │   │   ├── PostgreSQLPacket.java      # PostgreSQL packet handling
│   │       │   │   ├── PostgreSQLHandshake.java   # PostgreSQL handshake and authentication
│   │       │   │   └── PostgreSQLResultSet.java   # PostgreSQL result set handling
│   │       │   ├── AbstractProtocolAdapter.java   # Abstract base class for adapters
│   │       │   ├── MySqlProtocolAdapter.java      # MySQL protocol implementation
│   │       │   ├── PostgreSQLProtocolAdapter.java # PostgreSQL protocol implementation
│   │       │   └── ProtocolAdapter.java           # Protocol adapter interface
│   │       ├── parser/            # SQL parsing functionality using Alibaba Druid
│   │       │   ├── DruidSqlParser.java        # Druid-based SQL parser
│   │       │   ├── SqlDialect.java            # SQL dialect enum
│   │       │   ├── SqlParseException.java     # SQL parsing exception
│   │       │   └── SqlParser.java             # SQL parser interface
│   │       ├── service/           # Database connection services
│   │       │   └── DatabaseConnectionService.java # Database connection management
│   │       ├── config/            # Configuration classes
│   │       │   └── GatewayConfig.java         # Spring configuration
│   │       ├── controller/        # REST controllers
│   │       │   └── GatewayController.java     # Gateway REST controller
│   │       ├── Application.java   # Main Spring Boot application class
│   │       ├── ParserDemo.java    # Simple demo class for testing SQL parser
│   │       └── CommandLineInterface.java # CLI for gateway control
│   └── resources/
│       └── application.yml        # Configuration file
└── test/
    └── java/
        └── com.whosly.gateway/
            ├── adapter/
            │   └── MySqlProtocolAdapterTest.java  # Unit tests for MySQL protocol adapter
            └── parser/
                └── DruidSqlParserTest.java  # Unit tests for SQL parser
```

## Testing

Unit tests can be run with:

```bash
D:\devlops\apache-maven-3.9.9\bin\mvn test
```

## License

This project is licensed under the MIT License - see the LICENSE file for details.

## Support

For support, please open an issue in the repository.