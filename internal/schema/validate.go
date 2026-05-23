package schema

import (
	"encoding/json"
	"fmt"
)

type objectSchema struct {
	Type       string                    `json:"type"`
	Required   []string                  `json:"required"`
	Properties map[string]propertySchema `json:"properties"`
}

type propertySchema struct {
	Type     string          `json:"type"`
	Enum     json.RawMessage `json:"enum"`
	Items    *propertySchema `json:"items"`
	MinItems *int            `json:"minItems"`
}

func ValidateResponse(schemaJSON, payloadJSON []byte) error {
	if len(schemaJSON) == 0 || string(schemaJSON) == "{}" {
		return nil
	}
	var schema objectSchema
	if err := json.Unmarshal(schemaJSON, &schema); err != nil {
		return fmt.Errorf("request schema is invalid: %w", err)
	}
	if schema.Type != "" && schema.Type != "object" {
		return fmt.Errorf("only object response schemas are supported")
	}
	var payload map[string]any
	if err := json.Unmarshal(payloadJSON, &payload); err != nil {
		return fmt.Errorf("response payload is not a JSON object")
	}
	for _, field := range schema.Required {
		if _, ok := payload[field]; !ok {
			return fmt.Errorf("response missing required field %q", field)
		}
	}
	for field, prop := range schema.Properties {
		value, ok := payload[field]
		if !ok || value == nil {
			continue
		}
		if err := validatePropertyValue(field, prop, value); err != nil {
			return err
		}
	}
	return nil
}

func validatePropertyValue(field string, prop propertySchema, value any) error {
	if prop.Type != "" && !matchesType(value, prop.Type) {
		return fmt.Errorf("response field %q must be %s", field, prop.Type)
	}
	if len(prop.Enum) > 0 && string(prop.Enum) != "null" {
		var allowed []any
		if err := json.Unmarshal(prop.Enum, &allowed); err != nil {
			return fmt.Errorf("schema enum for %q is invalid", field)
		}
		if !containsJSONValue(allowed, value) {
			return fmt.Errorf("response field %q is not an allowed value", field)
		}
	}
	if items, ok := value.([]any); ok {
		if prop.MinItems != nil && len(items) < *prop.MinItems {
			return fmt.Errorf("response field %q must include at least %d item(s)", field, *prop.MinItems)
		}
		if prop.Items != nil {
			for index, item := range items {
				if err := validatePropertyValue(fmt.Sprintf("%s[%d]", field, index), *prop.Items, item); err != nil {
					return err
				}
			}
		}
	}
	return nil
}

func matchesType(value any, typ string) bool {
	switch typ {
	case "string":
		_, ok := value.(string)
		return ok
	case "boolean":
		_, ok := value.(bool)
		return ok
	case "number":
		_, ok := value.(float64)
		return ok
	case "integer":
		v, ok := value.(float64)
		return ok && v == float64(int64(v))
	case "object":
		_, ok := value.(map[string]any)
		return ok
	case "array":
		_, ok := value.([]any)
		return ok
	default:
		return true
	}
}

func containsJSONValue(values []any, needle any) bool {
	needleJSON, _ := json.Marshal(needle)
	for _, value := range values {
		valueJSON, _ := json.Marshal(value)
		if string(valueJSON) == string(needleJSON) {
			return true
		}
	}
	return false
}
