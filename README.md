# Focus-Aware Notification Deferral

An Android application that uses the Muse 2 EEG headset to estimate user focus and manage notifications to reduce distractions during focused periods.

## Project Overview

Focus-Aware Notification Deferral is a Kotlin-based Android application developed as part of COMPX551 Assignment 4 at the University of Waikato.

The application integrates with the Muse 2 EEG headset to collect brain activity data and estimate the user's focus state. When the application detects a focused period, it enables Android Focus Mode to reduce notification interruptions and keeps track of incoming notifications for a catch-up summary.

The main goal is to help students, professionals and other users maintain concentration while working or studying.

## Key Features

- **Muse 2 Integration:** Connects to the Muse 2 EEG headset using Bluetooth Low Energy and the LibMuse SDK.
- **EEG Data Collection:** Receives alpha, beta, theta and gamma band-power data, alongside supporting sensor information.
- **Signal Processing:** Filters unreliable EEG readings and calculates an engagement index.
- **Personalised Calibration:** Uses LOW, MEDIUM and HIGH task conditions to prepare individual focus thresholds.
- **Focus Detection:** Estimates the user's state as FOCUSED, NOT_FOCUSED or UNKNOWN.
- **Notification Management:** Uses Android's Do Not Disturb functionality to reduce interruptions during focused periods.
- **Catch-Up Summary:** Groups tracked deferred notifications into a single summary when the focused period ends.
- **Session Monitoring:** Records focus decisions and session statistics.

## System Architecture

The application is organised into four main modules:

| Module | Responsibility |
|---|---|
| Sensing | Connects to Muse 2 and collects EEG data. |
| Signal Processing & Calibration | Filters EEG data, calculates engagement and determines focus thresholds. |
| Notification Control | Manages Focus Mode, tracks notifications and creates catch-up summaries. |
| UI & Statistics | Provides the application interface and displays session information. |

### Data Flow

Muse 2 Headset  
↓  
Bluetooth Low Energy / LibMuse SDK  
↓  
EEG Signal Processing  
↓  
Focus State Machine  
↓  
Notification Controller  
↓  
Android Focus Mode and Catch-Up Summary

Calibration provides personalised thresholds to the focus detection system.

## Technologies Used

- Kotlin
- Android Studio
- Jetpack Compose
- Android SDK
- Muse 2 EEG Headset
- LibMuse SDK
- Bluetooth Low Energy (BLE)
- Kotlin Coroutines and Flow
- Android NotificationListenerService
- Android AutomaticZenRule API

## Notification Management

The notification module uses the estimated focus state to control notification handling.

- When the user is focused, the application enables its configured Do Not Disturb policy.
- Incoming notifications are tracked by the notification listener and added to a deferral queue where applicable.
- When focus ends, the application disables Focus Mode and presents a grouped catch-up notification.
- If the EEG signal becomes unreliable or the service stops, the application follows a fail-open approach to avoid unnecessarily blocking notifications.

**Note:** The current implementation uses Android Focus Mode and notification tracking rather than true system-level notification snoozing.

## Calibration and Focus Detection

The application uses three calibration conditions:

- **LOW:** Low-demand condition.
- **MEDIUM:** Moderate mental-demand task.
- **HIGH:** Higher mental-demand task.

Calibration recordings are checked for task performance, settling time and EEG artifacts before being used to fit personalised thresholds.

The Focus State Machine uses separate entry and exit thresholds with dwell times to reduce rapid changes between focus states.

## Privacy

The application is designed to process EEG data on-device. Its purpose is to estimate focus locally and manage notifications without sending EEG data to an external cloud service.

## My Contribution

I worked on the **Notification Control module** as part of a four-member development team.

My responsibilities included:

- Implementing notification monitoring using NotificationListenerService.
- Developing the NotificationController to coordinate notification handling.
- Implementing Do Not Disturb management using AutomaticZenRule.
- Developing the notification deferral queue.
- Implementing catch-up notification summaries.
- Handling notification policies and fail-open behaviour.

## Project Structure

The project is organised into separate packages:

- `device/` – Muse headset integration.
- `service/` – Background focus monitoring service.
- `signal/` – EEG processing and focus detection.
- `calibration/` – Personalised calibration and threshold fitting.
- `notification/` – Notification control and management.
- `core/` – Shared interfaces and data models.
- `data/` – Data persistence.
- `ui/` – Application interface and statistics.


## Academic Project

**Course:** COMPX551 – Mobile and Wearable Computing  
**Development:** Collaborative team project using Kotlin and Android Studio

