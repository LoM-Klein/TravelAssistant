package com.google.a2a.client.model.vo;


import com.fasterxml.jackson.annotation.JsonAnyGetter;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class QueryRequest {
    String userId;
    String query;
    String contextId;
}
