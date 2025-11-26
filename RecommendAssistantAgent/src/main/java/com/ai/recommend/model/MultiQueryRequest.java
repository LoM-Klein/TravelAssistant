package com.ai.recommend.model;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class MultiQueryRequest {
    private List<SpecializedQuery> queries;
}
