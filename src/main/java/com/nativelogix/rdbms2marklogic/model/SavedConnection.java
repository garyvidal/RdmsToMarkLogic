package com.nativelogix.rdbms2marklogic.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class SavedConnection {
    private String id;
    private String name;
    private ConnectionEnvironment environment;
    private Connection connection;

    public SavedConnection() {}

    public SavedConnection(String id, String name, ConnectionEnvironment environment, Connection connection) {
        this.id = id;
        this.name = name;
        this.environment = environment;
        this.connection = connection;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public ConnectionEnvironment getEnvironment() { return environment; }
    public void setEnvironment(ConnectionEnvironment environment) { this.environment = environment; }

    public Connection getConnection() { return connection; }
    public void setConnection(Connection connection) { this.connection = connection; }
}
