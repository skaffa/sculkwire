package dev.sculkwire.core.model;

import com.google.gson.JsonElement;

import dev.sculkwire.core.value.Value;
import dev.sculkwire.core.value.ValueType;

import java.util.List;

/** A user-defined variable: STRING, NUMBER, BOOL or LIST. */
public final class UserVariable {
	public String name = "";
	public String type = "STRING";
	public JsonElement value;

	public static UserVariable of(String name, Value v) {
		UserVariable u = new UserVariable();
		u.name = name;
		u.type = v.type().name();
		u.value = v.toJson();
		return u;
	}

	public Value toValue() {
		ValueType t = ValueType.parse(type);
		Value v = Value.fromJson(value);
		if (v.isNone()) {
			return t == ValueType.LIST ? Value.list(List.of()) : t == ValueType.STRING ? Value.str("") : Value.NONE;
		}
		return v.coerce(t);
	}
}
