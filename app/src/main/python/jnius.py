# jnius.py
from java import jclass
from com.chaquo.python import Python

def autoclass(class_name):
    """
    Spoofs Pyjnius. Intercepts Kivy-specific calls and reroutes
    them to Chaquopy, while passing standard Java classes through normally.
    """

    # 1. Intercept usb4a looking for Kivy's Activity OR Service
    if class_name in ('org.kivy.android.PythonActivity', 'org.kivy.android.PythonService'):
        class MockKivyContext:
            # Grab Chaquopy's Android Application Context
            context = Python.getPlatform().getApplication()

            # Map it to the variable names usb4a expects
            mActivity = context
            mService = context

        return MockKivyContext

    # 2. Let all standard Android/Java classes pass through to Chaquopy
    return jclass(class_name)