package com.example.smartguiderepo;

/**
 * Senior Developer Note: Centralizing strings here makes localizing
 * (adding more languages) much easier in the future.
 */
public class HelpManager {

    // Main instructions for first-time users or general help
    public static String getFullOnboarding(boolean isEnglish) {
        if (isEnglish) {
            return "Welcome to Smart Guide. I am here to help you navigate. " +
                    "You can say 'Start Camera' for indoor detection, or " +
                    "say 'Find' followed by an object name to search for something specific. " +
                    "You can also say 'Change Language' at any time. " +
                    "While detecting, say 'Stop' to pause or 'Start' to resume. " +
                    "How can I help you today?";
        } else {
            return "اسمارٹ گائیڈ میں خوش آمدید۔ میں آپ کی رہنمائی کے لیے حاضر ہوں۔ " +
                    "آپ ان ڈور ڈٹیکشن کے لیے 'کیمرہ شروع کریں' کہہ سکتے ہیں، یا " +
                    "مخصوص چیز تلاش کرنے کے لیے 'تلاش' کے بعد چیز کا نام کہیں۔ " +
                    "آپ کسی بھی وقت 'زبان تبدیل کریں' بھی کہہ سکتے ہیں۔ " +
                    "ڈٹیکشن کے دوران، روکنے کے لیے 'روکو' یا دوبارہ شروع کرنے کے لیے 'شروع' کہیں۔ " +
                    "میں آج آپ کی کیا مدد کر سکتا ہوں؟";
        }
    }

    // Intelligence for specific questions
    public static String getSpecificHelp(String query, boolean isEnglish) {
        query = query.toLowerCase();
        if (isEnglish) {
            if (query.contains("vibration") || query.contains("shake"))
                return "Fast vibration means an obstacle is very close. Slow pulses mean the path is clearer.";
            if (query.contains("stop") || query.contains("pause"))
                return "Just say 'Stop' while the camera is on to pause all detection and feedback.";
            if (query.contains("search") || query.contains("find"))
                return "Say 'Find' and then the object name, like 'Find Chair' or 'Find Bottle'.";
            return "I didn't quite get that. You can ask about vibrations, stopping the app, or finding objects.";
        } else {
            if (query.contains("وائبریشن") || query.contains("جھٹکا"))
                return "تیز وائبریشن کا مطلب ہے کہ رکاوٹ بہت قریب ہے۔";
            if (query.contains("تلاش"))
                return "کسی چیز کو تلاش کرنے کے لیے کہیں 'بوتل تلاش کرو'۔";
            return "معذرت، میں سمجھ نہیں سکا۔ آپ تلاش یا وائبریشن کے بارے میں پوچھ سکتے ہیں۔";
        }
    }
}