export const VERSION = "fair-tests-1.0.0";
export const copy = (en, hi) => ({ en, hi });
export const lesson = copy(
  "A fair test changes the factor being investigated while keeping other relevant conditions the same. Measure the outcome consistently. Repeating a test helps reveal variation. If several conditions change together, the result alone cannot tell you which caused the difference. An explanation is useful only when the evidence supports it.",
  "एक fair test में जिस factor की जाँच कर रहे हैं, केवल उसे बदलते हैं और दूसरी संबंधित conditions समान रखते हैं। Outcome को एक ही तरीके से मापें। Test दोहराने से variation का पता चलता है। यदि कई conditions एक साथ बदलें, तो केवल result से कारण तय नहीं किया जा सकता। Explanation तभी उपयोगी है जब evidence उसका समर्थन करे।",
);
export const tasks = {
  baseline: {
    title: copy(
      "Does fertilizer make plants grow?",
      "क्या fertilizer से पौधे अधिक बढ़ते हैं?",
    ),
    context: copy(
      "Two groups of bean plants start at the same height. After 14 days:",
      "Bean plants के दो समूह समान ऊँचाई से शुरू होते हैं। 14 दिन बाद:",
    ),
    columns: [
      copy("Group", "समूह"),
      copy("Fertilizer", "Fertilizer"),
      copy("Light / day", "प्रकाश / दिन"),
      copy("Water / day", "पानी / दिन"),
      copy("Growth", "वृद्धि"),
    ],
    rows: [
      ["A", "Yes / हाँ", "8 h", "100 mL", "8 cm"],
      ["B", "No / नहीं", "4 h", "100 mL", "3 cm"],
    ],
    claim: copy(
      "“Fertilizer definitely caused all the extra growth.”",
      "“पूरी अतिरिक्त वृद्धि निश्चित रूप से fertilizer के कारण हुई।”",
    ),
    questions: [
      {
        id: "evidence",
        prompt: copy(
          "Which observation matters most when checking this claim?",
          "इस दावे की जाँच में कौन-सा observation सबसे महत्वपूर्ण है?",
        ),
        options: [
          copy(
            "The groups received different amounts of light.",
            "दोनों समूहों को अलग मात्रा में प्रकाश मिला।",
          ),
          copy(
            "Group A grew more, so no other check is needed.",
            "समूह A अधिक बढ़ा, इसलिए दूसरी जाँच की जरूरत नहीं है।",
          ),
          copy("Both groups received water.", "दोनों समूहों को पानी मिला।"),
        ],
        answer: 0,
      },
      {
        id: "investigation",
        prompt: copy(
          "Which follow-up would best test the fertilizer effect?",
          "Fertilizer का प्रभाव जाँचने के लिए कौन-सा अगला test बेहतर है?",
        ),
        options: [
          copy(
            "Give the fertilized group more light and water.",
            "Fertilizer वाले समूह को अधिक प्रकाश और पानी दें।",
          ),
          copy(
            "Use comparable plants with equal light and water; vary only fertilizer and repeat.",
            "समान plants, प्रकाश और पानी रखें; केवल fertilizer बदलें और test दोहराएँ।",
          ),
          copy(
            "Measure only the tallest plant.",
            "केवल सबसे ऊँचे पौधे को मापें।",
          ),
        ],
        answer: 1,
      },
      {
        id: "judgment",
        prompt: copy(
          "What conclusion does this evidence support?",
          "यह evidence किस निष्कर्ष का समर्थन करता है?",
        ),
        options: [
          copy("Fertilizer never works.", "Fertilizer कभी काम नहीं करता।"),
          copy(
            "Fertilizer explains the entire difference.",
            "पूरा अंतर fertilizer के कारण है।",
          ),
          copy(
            "The groups grew differently, but the fertilizer effect is not isolated.",
            "वृद्धि अलग हुई, पर fertilizer का प्रभाव अलग से नहीं मापा गया।",
          ),
        ],
        answer: 2,
      },
    ],
  },
  guided: {
    title: copy(
      "Which paper towel absorbs more?",
      "कौन-सा paper towel अधिक पानी सोखता है?",
    ),
    context: copy(
      "A class compares equal-size sheets using the same amount of water and the same method. Each brand is tested three times.",
      "एक class समान आकार की sheets, पानी की समान मात्रा और एक ही विधि से तुलना करती है। हर brand का test तीन बार किया गया।",
    ),
    columns: [
      copy("Brand", "Brand"),
      copy("Trial 1", "Trial 1"),
      copy("Trial 2", "Trial 2"),
      copy("Trial 3", "Trial 3"),
    ],
    rows: [
      ["A", "20 mL", "22 mL", "21 mL"],
      ["B", "12 mL", "13 mL", "11 mL"],
    ],
    claim: copy(
      "“Under these test conditions, brand A absorbed more water than brand B.”",
      "“इन test conditions में brand A ने brand B से अधिक पानी सोखा।”",
    ),
    questions: [
      {
        id: "evidence",
        prompt: copy(
          "Which evidence is most relevant?",
          "कौन-सा evidence सबसे संबंधित है?",
        ),
        options: [
          copy(
            "Brand A has a nicer package.",
            "Brand A का package अधिक अच्छा है।",
          ),
          copy(
            "A absorbed more water in all three comparable trials.",
            "तीनों comparable trials में A ने अधिक पानी सोखा।",
          ),
          copy(
            "A must be best for every possible use.",
            "A हर उपयोग के लिए सबसे अच्छा होना चाहिए।",
          ),
        ],
        answer: 1,
      },
      {
        id: "investigation",
        prompt: copy(
          "How could the class check whether this pattern is repeatable?",
          "Class कैसे जाँच सकती है कि यह pattern दोहराया जा सकता है?",
        ),
        options: [
          copy(
            "Repeat with more equal-size sheets using the same method.",
            "समान आकार की और sheets पर उसी विधि से test दोहराएँ।",
          ),
          copy(
            "Use larger sheets only for brand A.",
            "केवल A के लिए बड़ी sheets लें।",
          ),
          copy(
            "Stop recording results that disagree.",
            "अलग results को लिखना बंद कर दें।",
          ),
        ],
        answer: 0,
      },
      {
        id: "judgment",
        prompt: copy(
          "How should you judge the explanation?",
          "Explanation का मूल्यांकन कैसे करें?",
        ),
        options: [
          copy(
            "Reject it just because an AI might say it.",
            "केवल इसलिए अस्वीकार करें क्योंकि AI ऐसा कह सकता है।",
          ),
          copy(
            "Accept it as proof that A is always better.",
            "इसे इस बात का प्रमाण मानें कि A हमेशा बेहतर है।",
          ),
          copy(
            "Accept the limited claim because the comparable trials support it.",
            "सीमित दावे को स्वीकार करें क्योंकि comparable trials उसका समर्थन करते हैं।",
          ),
        ],
        answer: 2,
      },
    ],
  },
  transfer: {
    title: copy(
      "Does warmer water dissolve sugar faster?",
      "क्या गर्म पानी में sugar जल्दी घुलती है?",
    ),
    context: copy(
      "Equal amounts of sugar and water are used. The learner measures time until the sugar dissolves.",
      "Sugar और पानी की समान मात्रा ली गई। Sugar घुलने तक का समय मापा गया।",
    ),
    columns: [
      copy("Cup", "Cup"),
      copy("Water", "पानी"),
      copy("Stirring", "हिलाना"),
      copy("Time", "समय"),
    ],
    rows: [
      ["A", "40°C", "Yes / हाँ", "20 s"],
      ["B", "20°C", "No / नहीं", "80 s"],
    ],
    claim: copy(
      "“The warmer temperature alone explains why cup A dissolved sugar faster.”",
      "“केवल अधिक temperature के कारण cup A में sugar जल्दी घुली।”",
    ),
    questions: [
      {
        id: "evidence",
        prompt: copy(
          "Which detail is essential when evaluating the claim?",
          "दावे की जाँच में कौन-सा detail जरूरी है?",
        ),
        options: [
          copy(
            "Cup A dissolved sugar faster, so the cause is settled.",
            "Cup A में sugar जल्दी घुली, इसलिए कारण तय है।",
          ),
          copy(
            "Both temperature and stirring differed.",
            "Temperature और stirring दोनों अलग थे।",
          ),
          copy(
            "Both containers are called cups.",
            "दोनों containers को cups कहते हैं।",
          ),
        ],
        answer: 1,
      },
      {
        id: "investigation",
        prompt: copy(
          "Which experiment would better isolate temperature?",
          "कौन-सा experiment temperature का प्रभाव अलग से जाँचेगा?",
        ),
        options: [
          copy(
            "Change the sugar amount as well as temperature.",
            "Temperature के साथ sugar की मात्रा भी बदलें।",
          ),
          copy(
            "Use different cup sizes and stir just one.",
            "अलग cup sizes लें और केवल एक को हिलाएँ।",
          ),
          copy(
            "Use comparable cups, equal sugar and water, and identical stirring; vary temperature and repeat.",
            "समान cups, sugar, पानी और stirring रखें; temperature बदलें और दोहराएँ।",
          ),
        ],
        answer: 2,
      },
      {
        id: "judgment",
        prompt: copy(
          "Which conclusion is justified now?",
          "अभी कौन-सा निष्कर्ष उचित है?",
        ),
        options: [
          copy(
            "The result does not separate the effects of temperature and stirring.",
            "Result temperature और stirring के प्रभाव अलग नहीं करता।",
          ),
          copy(
            "Temperature has no effect.",
            "Temperature का कोई प्रभाव नहीं है।",
          ),
          copy(
            "Temperature alone caused the difference.",
            "केवल temperature से अंतर हुआ।",
          ),
        ],
        answer: 0,
      },
    ],
  },
};
export const hints = [
  copy(
    "Look at the conditions in the table. Which stayed the same, and which changed?",
    "Table की conditions देखें। कौन-सी समान रहीं और कौन-सी बदलीं?",
  ),
  copy(
    "Compare the measurements across repeated trials. Does the explanation stay within what was tested?",
    "दोहराए गए trials के measurements की तुलना करें। क्या explanation केवल जाँची गई conditions तक सीमित है?",
  ),
  copy(
    "Describe one piece of evidence and one limit of the conclusion. What would you test next?",
    "एक evidence और निष्कर्ष की एक सीमा बताएँ। अगला test क्या होगा?",
  ),
];
