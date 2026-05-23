package schema

import "testing"

func TestValidateResponseSubset(t *testing.T) {
	schema := []byte(`{
	  "type":"object",
	  "properties":{
	    "decision":{"type":"string","enum":["approve","reject"]},
	    "comment":{"type":"string"}
	  },
	  "required":["decision"]
	}`)
	if err := ValidateResponse(schema, []byte(`{"decision":"approve","comment":"ok"}`)); err != nil {
		t.Fatal(err)
	}
	if err := ValidateResponse(schema, []byte(`{"decision":"maybe"}`)); err == nil {
		t.Fatal("expected enum validation error")
	}
	if err := ValidateResponse(schema, []byte(`{"comment":"missing"}`)); err == nil {
		t.Fatal("expected required validation error")
	}
}

func TestValidateResponseArrayItems(t *testing.T) {
	schema := []byte(`{
	  "type":"object",
	  "properties":{
	    "approved_items":{
	      "type":"array",
	      "items":{"type":"string","enum":["issue-1","issue-2","issue-3"]},
	      "minItems":1
	    }
	  }
	}`)
	if err := ValidateResponse(schema, []byte(`{"approved_items":["issue-1","issue-3"]}`)); err != nil {
		t.Fatal(err)
	}
	if err := ValidateResponse(schema, []byte(`{"approved_items":[]}`)); err == nil {
		t.Fatal("expected minItems validation error")
	}
	if err := ValidateResponse(schema, []byte(`{"approved_items":["issue-4"]}`)); err == nil {
		t.Fatal("expected item enum validation error")
	}
	if err := ValidateResponse(schema, []byte(`{"approved_items":"issue-1"}`)); err == nil {
		t.Fatal("expected array type validation error")
	}
}
