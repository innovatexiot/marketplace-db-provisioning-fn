# 🚀 Google Cloud Function in Java (Maven)

## 📌 Overview
This is a **Java-based Google Cloud Function** that:
- Accepts an HTTP request.
- Extracts a **query parameter** (`name`).
- Returns a personalized **greeting** message.
- Can be **Run locally**, **Debugged in IDE**, and **Deployed to GCP**.

## 🚀 Prerequisites
Ensure you have the following installed:
- **Java 17+** (`java -version`)
- **Maven** (`mvn -version`)
- **Google Cloud SDK** (`gcloud version`)
- **GCP Project Configured** (`gcloud init`)

## 🏃 Running, Testing, Deploying, Debugging, and Deleting the Function

## Build the Project
mvn clean package

## Run the Function Locally
mvn function:run
mvn function:run "-Drun.functionTarget=org.example.HelloWorldFunction"

## Test the Function Locally
curl "http://localhost:8080/?name=Java"

## Enable Cloud Functions and Cloud Run API
gcloud services enable cloudfunctions.googleapis.com
gcloud services enable run.googleapis.com

## Deploy the Function to Google Cloud
gcloud functions deploy helloFunction \
  --runtime java17 \
  --trigger-http \
  --allow-unauthenticated \
  --entry-point org.example.HelloWorldFunction \
  --region us-central1

## Get the Function URL
gcloud functions describe helloFunction

## Test the Deployed Function
curl "https://us-central1-YOUR_PROJECT_ID.cloudfunctions.net/helloFunction?name=John"

## Delete the Function from Google Cloud
gcloud functions delete helloFunction --region us-central1
