# Room and WorkManager ship their own keep rules. Nothing app-specific is reflected on.

# The widget's Taken button is looked up by class name when it's tapped.
-keep class * implements androidx.glance.appwidget.action.ActionCallback { <init>(); }
