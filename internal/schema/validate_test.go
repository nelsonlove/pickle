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
