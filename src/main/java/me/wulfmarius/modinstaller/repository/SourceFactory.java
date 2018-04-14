package me.wulfmarius.modinstaller.repository;

import java.util.Map;

public interface SourceFactory {

    Source create(String sourceDefinition, Map<String, ?> parameters);

    boolean isSupportedSource(String sourceDefinition);

}