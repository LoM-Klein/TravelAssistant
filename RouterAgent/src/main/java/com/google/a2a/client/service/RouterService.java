package com.google.a2a.client.service;

import com.google.a2a.client.model.RoutedRequest;
import com.google.a2a.client.model.RoutedResponse;

public interface RouterService {
    RoutedResponse route(RoutedRequest request);
}
