package com.allog.allogbe.routineverification.domain;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.List;

@Converter
public class StringListJsonConverter implements AttributeConverter<List<String>, String> {

	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final TypeReference<List<String>> LIST_TYPE = new TypeReference<>() {
	};

	@Override
	public String convertToDatabaseColumn(List<String> attribute) {
		if (attribute == null) {
			return null;
		}
		try {
			return MAPPER.writeValueAsString(attribute);
		} catch (JsonProcessingException e) {
			throw new IllegalStateException("Failed to serialize list to JSON", e);
		}
	}

	@Override
	public List<String> convertToEntityAttribute(String dbData) {
		if (dbData == null || dbData.isBlank()) {
			return List.of();
		}
		try {
			return MAPPER.readValue(dbData, LIST_TYPE);
		} catch (JsonProcessingException e) {
			throw new IllegalStateException("Failed to deserialize JSON to list", e);
		}
	}
}
