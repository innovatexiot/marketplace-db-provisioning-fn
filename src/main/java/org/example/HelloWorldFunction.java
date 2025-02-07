package org.example;

import com.google.cloud.functions.HttpFunction;
import com.google.cloud.functions.HttpRequest;
import com.google.cloud.functions.HttpResponse;

import java.io.BufferedWriter;

public class HelloWorldFunction implements HttpFunction {
    @Override
    public void service(HttpRequest request, HttpResponse response) throws Exception {
        // Get the "name" query parameter (default to "World" if not provided)
        String name = request.getFirstQueryParameter("name").orElse("World");

        // Write the response
        BufferedWriter writer = response.getWriter();
        writer.write("Hello, " + name + "! Welcome to Google Cloud Functions.");
    }
}

