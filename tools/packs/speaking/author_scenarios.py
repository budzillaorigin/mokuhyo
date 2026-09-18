"""Authoring source for tools/packs/speaking/scenarios.json (LLM-drafted, source="llm", awaiting human review).

Kept as compact Python so the JSON is regenerated reproducibly: uv run python packs/speaking/author_scenarios.py
Each turn is (partner line ja, partner line en, what the learner should do, one acceptable learner reply).
"""

from __future__ import annotations

import json
from pathlib import Path

S = []


def scenario(id_, title_en, title_ja, jlpt, ilr, category, setting, learner, partner, register, goals, vocab, phrases, turns):
    S.append({
        "id": id_, "titleEn": title_en, "titleJa": title_ja, "jlpt": jlpt, "ilr": ilr, "category": category,
        "setting": setting, "learnerRole": learner, "partnerRole": partner, "register": register,
        "goals": goals, "vocabulary": vocab, "phrases": phrases,
        "turns": [{"partnerJa": t[0], "partnerEn": t[1], "intent": t[2], "sample": t[3]} for t in turns],
    })


scenario("konbini", "At the convenience store", "コンビニで", 5, "0+", "daily",
    "A busy convenience store at lunchtime. You are buying a bento and a drink.",
    "Customer", "Store clerk, friendly and quick", "polite",
    ["Say whether you want the bento heated", "Ask for a bag or decline one", "Pay and say thank you"],
    ["弁当", "温める", "袋", "要る", "箸", "カード", "現金"],
    ["温めてください", "袋はいりません", "カードでお願いします", "ありがとうございます"],
    [("いらっしゃいませ。お弁当、温めますか。", "Welcome. Shall I heat up your bento?", "Answer yes or no about heating", "はい、お願いします。"),
     ("袋はご利用ですか。", "Would you like a bag?", "Accept or decline a bag", "いいえ、大丈夫です。"),
     ("お箸はおつけしますか。", "Shall I add chopsticks?", "Ask for chopsticks", "はい、一つお願いします。"),
     ("お会計は八百五十円です。", "That comes to 850 yen.", "Say how you will pay", "カードでお願いします。"),
     ("こちらにカードをどうぞ。", "Please insert your card here.", "Acknowledge", "はい。"),
     ("ありがとうございました。", "Thank you very much.", "Thank the clerk", "ありがとうございます。")])

scenario("izakaya", "Ordering at an izakaya", "居酒屋で注文する", 4, "1", "daily",
    "A lively izakaya on a Friday evening. You and a friend have just sat down.",
    "Customer", "Energetic waiter", "polite",
    ["Order drinks", "Ask for a recommendation", "Order two dishes", "Ask for the bill"],
    ["生ビール", "注文", "おすすめ", "焼き鳥", "枝豆", "お会計", "メニュー"],
    ["とりあえず生二つ", "おすすめは何ですか", "これをください", "お会計をお願いします"],
    [("いらっしゃいませ！お飲み物はいかがですか。", "Welcome! What would you like to drink?", "Order drinks", "生ビールを二つお願いします。"),
     ("かしこまりました。お料理はお決まりですか。", "Certainly. Have you decided on food?", "Ask for a recommendation", "おすすめは何ですか。"),
     ("今日は焼き鳥がおすすめです。", "Today the yakitori is our recommendation.", "Order it and another dish", "じゃあ、焼き鳥と枝豆をください。"),
     ("焼き鳥は塩とたれ、どちらにしますか。", "Salt or sauce for the yakitori?", "Choose one", "たれでお願いします。"),
     ("以上でよろしいですか。", "Will that be all?", "Confirm", "はい、以上です。"),
     ("ほかにご注文はありますか。", "Anything else to order?", "Ask for the bill", "いいえ、お会計をお願いします。")])

scenario("doctor", "At the doctor's", "病院で", 4, "1", "health",
    "A small clinic. You have had a fever and a sore throat since yesterday.",
    "Patient", "Calm, careful doctor", "polite",
    ["Describe your symptoms", "Say since when", "Answer about allergies", "Ask how to take the medicine"],
    ["熱", "喉", "痛い", "咳", "薬", "アレルギー", "昨日"],
    ["昨日から熱があります", "喉が痛いです", "アレルギーはありません", "一日何回飲みますか"],
    [("今日はどうしましたか。", "What brings you in today?", "Describe symptoms", "熱があって、喉が痛いです。"),
     ("いつからですか。", "Since when?", "Say since when", "昨日の夜からです。"),
     ("咳は出ますか。", "Do you have a cough?", "Answer", "少し出ます。"),
     ("薬のアレルギーはありますか。", "Any allergies to medicine?", "Answer about allergies", "いいえ、ありません。"),
     ("風邪ですね。薬を出しておきます。", "It's a cold. I'll prescribe some medicine.", "Ask how to take it", "一日何回飲みますか。"),
     ("一日三回、食後に飲んでください。", "Three times a day, after meals.", "Confirm and thank", "わかりました。ありがとうございました。")])

scenario("pharmacy", "At the pharmacy", "薬局で", 4, "1", "health",
    "A drugstore. You need something for a headache and want to check it is safe with your allergy.",
    "Customer", "Pharmacist", "polite",
    ["Explain what you need", "Mention an allergy", "Ask about side effects"],
    ["頭痛", "薬", "眠い", "副作用", "アレルギー", "飲む"],
    ["頭痛の薬はありますか", "眠くなりますか", "これを飲んでも大丈夫ですか"],
    [("何かお探しですか。", "Can I help you find something?", "Say what you need", "頭痛の薬はありますか。"),
     ("こちらはいかがでしょう。", "How about this one?", "Mention an allergy", "私はアスピリンのアレルギーがあります。"),
     ("では、こちらのほうが安心です。", "Then this one is safer.", "Ask about side effects", "眠くなりますか。"),
     ("少し眠くなることがあります。運転はしないでください。", "It may make you a little sleepy. Please don't drive.", "Acknowledge", "わかりました。"),
     ("一回二錠です。", "Two tablets per dose.", "Ask how often", "一日何回ですか。"),
     ("四時間以上あけて、一日三回までです。", "At least four hours apart, up to three times a day.", "Thank", "ありがとうございます。これをください。")])

scenario("station-lost", "Lost item at the station", "駅で忘れ物", 4, "1", "travel",
    "A station office. You left your umbrella on the train twenty minutes ago.",
    "Passenger", "Station staff member", "polite",
    ["Explain what you lost", "Describe it", "Say which train and when", "Leave contact details"],
    ["忘れ物", "傘", "電車", "色", "連絡", "電話番号"],
    ["電車に傘を忘れました", "青い傘です", "電話番号は…です"],
    [("どうされましたか。", "What happened?", "Explain the problem", "電車に傘を忘れてしまいました。"),
     ("どの電車ですか。", "Which train?", "Say which train and when", "二十分前の東京行きです。"),
     ("どんな傘ですか。", "What does the umbrella look like?", "Describe it", "青くて、長い傘です。"),
     ("何両目に乗っていましたか。", "Which car were you in?", "Answer (or say you don't know)", "たぶん三両目です。"),
     ("見つかったら連絡します。お名前と電話番号をお願いします。", "We'll contact you if it's found. Your name and phone number, please.", "Give contact details", "スミスです。電話番号は〇九〇の…です。"),
     ("わかりました。お待ちください。", "Understood. Please wait.", "Thank", "よろしくお願いします。")])

scenario("directions", "Asking for directions", "道を聞く", 5, "0+", "travel",
    "A street corner near a station. You are looking for the post office.",
    "Tourist", "Kind passer-by", "polite",
    ["Get someone's attention politely", "Ask where the place is", "Confirm the directions"],
    ["郵便局", "道", "まっすぐ", "右", "左", "信号", "近く"],
    ["すみません", "郵便局はどこですか", "まっすぐですね"],
    [("はい、何でしょう。", "Yes, can I help?", "Ask where the post office is", "すみません、郵便局はどこですか。"),
     ("この道をまっすぐ行ってください。", "Go straight along this road.", "Ask how far", "遠いですか。"),
     ("いいえ、歩いて五分ぐらいです。", "No, about five minutes on foot.", "Ask for the next step", "それから、どうしますか。"),
     ("二つ目の信号を右に曲がってください。", "Turn right at the second traffic light.", "Confirm", "二つ目の信号を右ですね。"),
     ("はい。郵便局は左にあります。", "Yes. The post office is on the left.", "Thank", "どうもありがとうございます。"),
     ("気をつけて。", "Take care.", "Say goodbye", "失礼します。")])

scenario("hotel", "Hotel check-in", "ホテルのチェックイン", 4, "1", "travel",
    "A business hotel front desk in the evening.",
    "Guest with a reservation", "Front desk clerk", "keigo",
    ["Say you have a reservation", "Confirm dates", "Ask about breakfast", "Ask for Wi-Fi"],
    ["予約", "チェックイン", "朝食", "部屋", "鍵", "パスワード"],
    ["予約している…です", "朝食は何時からですか", "Wi-Fiのパスワードを教えてください"],
    [("いらっしゃいませ。チェックインでございますか。", "Welcome. Are you checking in?", "Say you have a reservation", "はい、予約しているブラウンです。"),
     ("ブラウン様ですね。二泊でございますね。", "Mr./Ms. Brown, for two nights, correct?", "Confirm", "はい、そうです。"),
     ("こちらにご記入をお願いいたします。", "Please fill this in.", "Acknowledge and ask about breakfast", "はい。朝食は何時からですか。"),
     ("朝食は七時から九時半まででございます。", "Breakfast is from 7:00 to 9:30.", "Ask for Wi-Fi", "Wi-Fiのパスワードを教えてください。"),
     ("こちらのカードに書いてございます。お部屋は八〇五号室です。", "It's on this card. Your room is 805.", "Take the key and thank", "ありがとうございます。"),
     ("ごゆっくりお過ごしくださいませ。", "Please enjoy your stay.", "Respond politely", "お世話になります。")])

scenario("phone-reschedule", "Rescheduling by phone", "電話で予定を変える", 3, "1+", "work",
    "You call a dental clinic to move tomorrow's appointment.",
    "Patient calling", "Receptionist", "polite",
    ["Identify yourself", "Explain you can't come", "Propose another day", "Confirm the new time"],
    ["予約", "変更", "都合", "明日", "来週", "空く"],
    ["明日の予約を変更したいんですが", "都合が悪くなってしまって", "来週の火曜日は空いていますか"],
    [("はい、さくら歯科です。", "Hello, Sakura Dental.", "Identify yourself and say why you call", "もしもし、明日十時に予約しているキムです。予約を変更したいんですが。"),
     ("かしこまりました。ご希望の日はありますか。", "Certainly. Is there a day you'd prefer?", "Propose another day", "来週の火曜日は空いていますか。"),
     ("火曜日は午後三時なら空いております。", "On Tuesday, 3 p.m. is free.", "Accept or ask for another time", "三時で大丈夫です。"),
     ("では、来週火曜日の午後三時でお取りします。", "Then I'll book you for next Tuesday at 3 p.m.", "Confirm the new time", "来週火曜日の三時ですね。"),
     ("はい。明日の予約はキャンセルしておきます。", "Yes. I'll cancel tomorrow's booking.", "Apologise for the trouble", "ご迷惑をおかけしてすみません。"),
     ("いいえ、お大事に。", "Not at all. Take care.", "Close the call", "失礼します。")])

scenario("job-interview", "Job interview", "面接", 3, "2", "work",
    "A formal interview for a part-time office job.",
    "Applicant", "Interviewer, polite but probing", "keigo",
    ["Introduce yourself formally", "Explain your motivation", "Describe a strength", "Ask one question at the end"],
    ["志望", "動機", "経験", "長所", "貢献", "質問"],
    ["よろしくお願いいたします", "御社を志望した理由は", "私の長所は"],
    [("では、自己紹介をお願いします。", "Please introduce yourself.", "Introduce yourself formally", "マリア・ロペスと申します。よろしくお願いいたします。"),
     ("志望動機を教えてください。", "Tell us why you applied.", "Explain motivation", "日本語を使って仕事をしたいと思い、応募いたしました。"),
     ("これまでにどんな経験がありますか。", "What experience do you have?", "Describe experience", "大学で事務のアルバイトを二年間しておりました。"),
     ("あなたの長所は何ですか。", "What is your strength?", "Describe a strength with an example", "私の長所は責任感が強いところです。"),
     ("いつから働けますか。", "When can you start?", "Answer", "来月から働けます。"),
     ("最後に何か質問はありますか。", "Finally, do you have any questions?", "Ask one question", "研修はどのように行われますか。")])

scenario("self-intro-work", "First day at work", "職場での自己紹介", 4, "1", "work",
    "Your first morning in a Japanese office. Your manager introduces you to the team.",
    "New employee", "Manager", "polite",
    ["Introduce yourself", "Say where you're from and your role", "Ask for guidance politely"],
    ["自己紹介", "出身", "担当", "同僚", "よろしく"],
    ["はじめまして", "…から来ました", "ご指導よろしくお願いします"],
    [("じゃあ、みんなに自己紹介してください。", "Please introduce yourself to everyone.", "Introduce yourself", "はじめまして、アレックスと申します。"),
     ("出身はどちらですか。", "Where are you from?", "Say where you are from", "アメリカのシアトルから来ました。"),
     ("こちらでは営業を担当してもらいます。", "Here you'll be in charge of sales.", "Respond with enthusiasm", "精一杯がんばります。"),
     ("何かわからないことがあれば、田中さんに聞いてください。", "If you don't understand something, ask Tanaka.", "Greet Tanaka", "田中さん、よろしくお願いします。"),
     ("日本の会社は初めてですか。", "Is this your first Japanese company?", "Answer", "はい、初めてです。"),
     ("では、今日からよろしく。", "Well then, welcome aboard.", "Close politely", "ご指導よろしくお願いいたします。")])

scenario("counterpart-briefing", "Briefing a Japanese counterpart", "日本側担当者への説明", 2, "2+", "official",
    "A meeting room on base. You brief a Japanese liaison officer about tomorrow's joint schedule.",
    "Briefing officer", "Japanese liaison officer, formal and precise", "keigo",
    ["State the purpose of the briefing", "Explain the schedule clearly", "Confirm points of contact", "Check for questions"],
    ["日程", "訓練", "集合", "担当者", "確認", "連絡"],
    ["本日は…についてご説明いたします", "集合時間は…です", "ご質問はございますか"],
    [("本日はよろしくお願いいたします。", "Thank you for your time today.", "State the purpose", "本日は明日の合同訓練の日程についてご説明いたします。"),
     ("集合は何時でしょうか。", "What time do we assemble?", "Explain the schedule", "集合は午前七時、第二格納庫前です。"),
     ("日本側の人数はどのくらいを想定されていますか。", "How many personnel do you expect from the Japanese side?", "Answer with numbers", "日本側は十五名を予定しております。"),
     ("雨天の場合はどうなりますか。", "What happens if it rains?", "Explain the contingency", "雨天の場合は屋内訓練に変更いたします。"),
     ("連絡窓口はどなたになりますか。", "Who is the point of contact?", "Name the point of contact", "連絡窓口は私、ジョンソン大尉が担当いたします。"),
     ("承知いたしました。", "Understood.", "Check for questions", "ほかにご質問はございますか。")])

scenario("base-gate", "Visitor check-in at the base gate", "基地ゲートでの受付", 3, "1+", "official",
    "The main gate of a base. A Japanese contractor arrives for a meeting; you are on gate duty.",
    "Gate guard", "Visiting contractor", "polite",
    ["Greet and ask the purpose of the visit", "Request ID", "Confirm the appointment", "Explain the visitor pass rules"],
    ["身分証明書", "訪問", "目的", "許可", "記入", "返却"],
    ["ご用件は何ですか", "身分証明書を見せてください", "お帰りの際に返却してください"],
    [("おはようございます。十時から会議があって来ました。", "Good morning. I'm here for a 10 o'clock meeting.", "Ask who they are meeting", "おはようございます。どなたとの会議ですか。"),
     ("施設課の佐藤さんです。", "With Mr. Sato from facilities.", "Request ID", "身分証明書を見せていただけますか。"),
     ("はい、運転免許証です。", "Yes, here is my driver's license.", "Ask them to fill out a form", "ありがとうございます。こちらの用紙に記入してください。"),
     ("これでいいですか。", "Is this all right?", "Confirm the appointment and issue a pass", "はい。佐藤さんに確認が取れました。こちらが通行証です。"),
     ("どこに行けばいいですか。", "Where should I go?", "Give directions", "まっすぐ行って、二つ目の建物です。"),
     ("わかりました。", "Understood.", "Explain the pass rules", "お帰りの際に、通行証を返却してください。")])

scenario("koban", "At the police box", "交番で", 4, "1", "official",
    "A koban. You found a wallet on the street and want to hand it in.",
    "Finder", "Police officer", "polite",
    ["Say what you found", "Say where and when", "Give your contact details"],
    ["財布", "拾う", "交番", "場所", "届ける", "連絡先"],
    ["財布を拾いました", "駅の前で拾いました", "連絡先は…です"],
    [("どうしましたか。", "What's the matter?", "Say what you found", "財布を拾ったので、届けに来ました。"),
     ("どこで拾いましたか。", "Where did you find it?", "Say where", "駅の前の道で拾いました。"),
     ("何時ごろですか。", "About what time?", "Say when", "十分ぐらい前です。"),
     ("中は見ましたか。", "Did you look inside?", "Answer", "いいえ、見ていません。"),
     ("では、お名前と連絡先をお願いします。", "Your name and contact details, please.", "Give contact details", "リーです。電話番号はこちらです。"),
     ("ご協力ありがとうございました。", "Thank you for your cooperation.", "Respond", "いいえ、よろしくお願いします。")])

scenario("bank", "Opening a bank account", "銀行で口座を作る", 3, "1+", "daily",
    "A bank branch. You want to open an account for your salary.",
    "Customer", "Bank clerk", "keigo",
    ["Say what you want to do", "Show residence card", "Answer questions about purpose", "Ask about the cash card"],
    ["口座", "開く", "在留カード", "印鑑", "目的", "給料"],
    ["口座を作りたいんですが", "在留カードはこちらです", "キャッシュカードはいつ届きますか"],
    [("本日はどのようなご用件でしょうか。", "How may I help you today?", "Say what you want", "口座を開きたいんですが。"),
     ("身分証明書はお持ちですか。", "Do you have identification?", "Show residence card", "はい、在留カードです。"),
     ("口座のご利用目的は何でしょうか。", "What is the account for?", "Explain purpose", "給料の振り込みに使います。"),
     ("印鑑はお持ちですか。", "Do you have a personal seal?", "Answer (or ask if a signature is okay)", "いいえ、サインでもいいですか。"),
     ("はい、サインで大丈夫です。", "Yes, a signature is fine.", "Ask about the cash card", "キャッシュカードはいつ届きますか。"),
     ("一週間ほどでご自宅に郵送いたします。", "We'll mail it to your home in about a week.", "Thank", "わかりました。ありがとうございます。")])

scenario("post-office", "Sending a package", "郵便局で荷物を送る", 4, "1", "daily",
    "A post office counter. You want to send a small package to the United States.",
    "Customer", "Postal clerk", "polite",
    ["Say where you're sending it", "Choose a shipping method", "Say what's inside"],
    ["荷物", "送る", "航空便", "船便", "中身", "届く"],
    ["アメリカに送りたいです", "航空便でお願いします", "中身は本です"],
    [("こちらの荷物はどちらへ送りますか。", "Where are you sending this package?", "Say the destination", "アメリカに送りたいです。"),
     ("航空便と船便、どちらにしますか。", "Airmail or sea mail?", "Ask about delivery times", "航空便だと何日ぐらいかかりますか。"),
     ("一週間ぐらいです。", "About a week.", "Choose a method", "じゃあ、航空便でお願いします。"),
     ("中身は何ですか。", "What's inside?", "Say what's inside", "本と服です。"),
     ("こちらの用紙に記入してください。", "Please fill in this form.", "Acknowledge", "はい、わかりました。"),
     ("料金は二千四百円です。", "That's 2,400 yen.", "Pay and thank", "はい、お願いします。")])

scenario("restaurant-allergy", "Food allergy at a restaurant", "レストランでアレルギーを伝える", 3, "1+", "health",
    "A family restaurant. You are allergic to shrimp and want to order safely.",
    "Customer", "Server", "polite",
    ["State the allergy clearly", "Ask about ingredients", "Order a safe dish"],
    ["アレルギー", "海老", "入る", "料理", "材料", "大丈夫"],
    ["海老のアレルギーがあります", "これに海老は入っていますか", "海老が入っていない料理はありますか"],
    [("ご注文はお決まりですか。", "Are you ready to order?", "State the allergy", "海老のアレルギーがあるんですが。"),
     ("かしこまりました。", "Understood.", "Ask about a dish", "このパスタに海老は入っていますか。"),
     ("はい、そちらには入っております。", "Yes, that one contains it.", "Ask for alternatives", "海老が入っていない料理はありますか。"),
     ("こちらのハンバーグなら大丈夫です。", "This hamburg steak is fine.", "Check cooking", "同じ鍋で作りますか。"),
     ("いいえ、別に調理いたします。", "No, we'll prepare it separately.", "Order it", "じゃあ、ハンバーグをお願いします。"),
     ("かしこまりました。", "Certainly.", "Thank", "ありがとうございます。")])

scenario("landlord", "Apartment problem", "アパートのトラブル", 3, "1+", "daily",
    "You call your building's management company because the water heater is broken.",
    "Tenant", "Management company staff", "polite",
    ["Explain the problem", "Say since when", "Arrange a repair visit"],
    ["お湯", "出る", "故障", "修理", "部屋", "都合"],
    ["お湯が出ないんです", "修理に来てもらえますか", "明日の午前中なら大丈夫です"],
    [("はい、みどり不動産です。", "Hello, Midori Real Estate.", "Say who you are", "さくらハイツ二〇三号室のパクです。"),
     ("どうされましたか。", "What's the problem?", "Explain the problem", "お湯が出なくなってしまったんです。"),
     ("いつからですか。", "Since when?", "Say since when", "今朝からです。"),
     ("修理業者に連絡します。ご都合のいい日はありますか。", "I'll contact a repair company. When is convenient?", "Arrange a time", "明日の午前中なら大丈夫です。"),
     ("では、明日の十時ごろ伺います。", "Then they'll come around 10 tomorrow.", "Confirm", "十時ですね。わかりました。"),
     ("ご不便をおかけします。", "Sorry for the inconvenience.", "Thank", "よろしくお願いします。")])

scenario("emergency-119", "Calling 119", "119番に電話する", 3, "1+", "health",
    "Someone has collapsed on the street. You call 119. Stay calm and give facts.",
    "Caller", "Emergency dispatcher, calm and structured", "polite",
    ["Say it's an ambulance call", "Give the location", "Describe the person's condition", "Follow instructions"],
    ["救急車", "場所", "倒れる", "意識", "呼吸", "住所"],
    ["救急車をお願いします", "人が倒れています", "意識がありません"],
    [("火事ですか、救急ですか。", "Fire or ambulance?", "Say ambulance", "救急です。救急車をお願いします。"),
     ("場所はどこですか。", "Where are you?", "Give the location", "中央駅の東口の前です。"),
     ("どうしましたか。", "What happened?", "Describe what happened", "男の人が倒れています。"),
     ("意識はありますか。", "Is he conscious?", "Describe condition", "意識がないみたいです。"),
     ("呼吸はしていますか。", "Is he breathing?", "Answer", "はい、呼吸はしています。"),
     ("救急車が向かっています。そのまま待ってください。", "An ambulance is on the way. Please wait there.", "Confirm", "わかりました。ここで待ちます。")])

scenario("returns", "Returning an item", "返品する", 3, "1+", "daily",
    "A clothing shop. The shirt you bought yesterday is the wrong size.",
    "Customer", "Shop staff", "polite",
    ["Explain the problem", "Show the receipt", "Choose exchange or refund"],
    ["返品", "交換", "サイズ", "レシート", "返金"],
    ["昨日買ったんですが", "サイズが合わなくて", "交換できますか"],
    [("いらっしゃいませ。", "Welcome.", "Explain the problem", "昨日このシャツを買ったんですが、サイズが合わなくて。"),
     ("レシートはお持ちですか。", "Do you have the receipt?", "Show it", "はい、こちらです。"),
     ("交換と返金、どちらにしますか。", "Exchange or refund?", "Choose", "Lサイズに交換できますか。"),
     ("少々お待ちください。…Lサイズ、ございました。", "One moment… we have a large.", "Try it or accept", "試着してもいいですか。"),
     ("どうぞ、あちらです。", "Please, over there.", "Report and decide", "ちょうどいいです。これにします。"),
     ("では、交換いたします。", "Then I'll exchange it.", "Thank", "ありがとうございました。")])

scenario("hair-salon", "At the hair salon", "美容院で", 3, "1+", "daily",
    "A hair salon. You want a trim, not a big change.",
    "Customer", "Stylist, chatty", "polite",
    ["Explain how you want your hair", "Answer small talk", "Give feedback at the end"],
    ["髪", "切る", "短い", "前髪", "少し", "長さ"],
    ["少しだけ切ってください", "前髪は眉ぐらいで", "ちょうどいいです"],
    [("今日はどうしますか。", "What would you like today?", "Explain", "少しだけ切ってください。"),
     ("どのくらい切りますか。", "How much should I cut?", "Say how much", "三センチぐらいお願いします。"),
     ("前髪はどうしますか。", "What about the fringe?", "Explain", "眉ぐらいの長さにしてください。"),
     ("お仕事帰りですか。", "Are you on your way home from work?", "Small talk", "はい、今日は早く終わりました。"),
     ("こんな感じでいかがですか。", "How does this look?", "Give feedback", "いいですね。ちょうどいいです。"),
     ("お疲れさまでした。", "All done, thank you.", "Thank", "ありがとうございました。")])

scenario("gym", "Signing up at a gym", "ジムに入会する", 3, "1+", "daily",
    "The front desk of a local gym.",
    "Prospective member", "Gym staff", "polite",
    ["Say you want to join", "Ask about fees and hours", "Ask about a trial"],
    ["入会", "料金", "月", "体験", "営業時間", "会員"],
    ["入会したいんですが", "月いくらですか", "体験はできますか"],
    [("こんにちは。見学ですか。", "Hello. Here to look around?", "Say you want to join", "入会したいと思っているんですが。"),
     ("ありがとうございます。何か質問はありますか。", "Thank you. Any questions?", "Ask about fees", "料金は月いくらですか。"),
     ("月八千円です。", "8,000 yen a month.", "Ask about hours", "営業時間は何時から何時までですか。"),
     ("朝七時から夜十一時までです。", "7 a.m. to 11 p.m.", "Ask about a trial", "体験はできますか。"),
     ("はい、一回無料で体験できます。", "Yes, you can try once for free.", "Book it", "じゃあ、明日体験したいです。"),
     ("では、お名前をお願いします。", "Your name, please.", "Give your name", "グエンです。")])

scenario("making-friends", "Making a friend", "友達を作る", 5, "0+", "social",
    "A language exchange café. Someone your age starts chatting.",
    "Learner", "Friendly student", "casual",
    ["Exchange names", "Talk about hobbies", "Suggest meeting again"],
    ["趣味", "名前", "好き", "映画", "今度", "一緒"],
    ["趣味は何？", "私も好き！", "今度一緒に行こう"],
    [("こんにちは！名前は？", "Hi! What's your name?", "Say your name", "サラです。よろしく！"),
     ("私はゆいです。どこから来たの？", "I'm Yui. Where are you from?", "Say where you're from", "カナダから来たよ。"),
     ("趣味は何？", "What are your hobbies?", "Talk about hobbies", "映画を見るのが好き。"),
     ("本当？私も映画、大好き！", "Really? I love movies too!", "Ask what kind", "どんな映画が好き？"),
     ("アニメ映画が好き。", "I like animated films.", "Suggest meeting", "今度一緒に見に行かない？"),
     ("いいね！行こう！", "Sounds good! Let's go!", "Exchange contact", "じゃあ、LINE交換しよう。")])

scenario("late-apology", "Apologising for being late", "遅刻を謝る", 4, "1", "work",
    "You arrive fifteen minutes late to a team meeting because the train was delayed.",
    "Team member", "Team leader, a little annoyed", "polite",
    ["Apologise sincerely", "Explain the reason briefly", "Say how you'll avoid it"],
    ["遅刻", "遅れる", "電車", "申し訳ない", "気をつける"],
    ["遅れて申し訳ありません", "電車が遅れてしまって", "今後気をつけます"],
    [("あ、来ましたか。", "Oh, you're here.", "Apologise", "遅れて申し訳ありません。"),
     ("どうしたんですか。", "What happened?", "Explain briefly", "電車が事故で止まってしまって。"),
     ("連絡がほしかったですね。", "I'd have liked a message.", "Accept and apologise", "連絡しなくてすみませんでした。"),
     ("次から気をつけてください。", "Please be careful from now on.", "Promise", "はい、今後気をつけます。"),
     ("今、予算の話をしていました。", "We were just discussing the budget.", "Ask to catch up", "あとで資料を見せていただけますか。"),
     ("いいですよ。", "Sure.", "Thank", "ありがとうございます。")])

scenario("decline-invitation", "Declining an invitation", "誘いを断る", 3, "1+", "social",
    "A coworker invites you to drinks tonight, but you already have plans.",
    "Coworker", "Friendly coworker", "polite",
    ["Thank them for the invitation", "Decline softly", "Suggest another time"],
    ["誘う", "今夜", "予定", "残念", "今度"],
    ["誘ってくれてありがとう", "今日はちょっと…", "また誘ってください"],
    [("今夜、みんなで飲みに行くんだけど、来ない？", "We're all going for drinks tonight, want to come?", "Thank them", "誘ってくれてありがとうございます。"),
     ("どう？", "So?", "Decline softly", "今日はちょっと予定があって…。"),
     ("そうか、残念。", "Oh, that's a shame.", "Suggest another time", "来週なら行けます。"),
     ("じゃあ、来週の金曜日はどう？", "How about next Friday then?", "Accept", "金曜日ならぜひ。"),
     ("決まりだね。", "It's settled then.", "Close warmly", "楽しみにしています。"),
     ("お疲れさま。", "Good work today.", "Say goodbye", "お疲れさまでした。")])

scenario("favor-senior", "Asking a senior colleague for a favor", "先輩にお願いする", 3, "2", "work",
    "You need a senior colleague to check your report before a deadline.",
    "Junior colleague", "Busy senior colleague (先輩)", "polite",
    ["Check if they have time", "Make the request with appropriate softness", "Thank them properly"],
    ["お願い", "報告書", "確認", "締め切り", "時間", "助かる"],
    ["今ちょっとよろしいですか", "…していただけませんか", "助かります"],
    [("ん？どうした？", "Hm? What's up?", "Check they have time", "先輩、今ちょっとよろしいですか。"),
     ("いいよ、何？", "Sure, what is it?", "Make the request", "この報告書を確認していただけませんか。"),
     ("締め切りはいつ？", "When's the deadline?", "Give the deadline", "明日の朝です。"),
     ("今日は忙しいから、夕方でもいい？", "I'm busy today, is this evening okay?", "Accept gratefully", "はい、夕方で大丈夫です。助かります。"),
     ("どこを特に見ればいい？", "What should I look at especially?", "Explain", "特に数字が合っているか見ていただきたいです。"),
     ("わかった。", "Got it.", "Thank properly", "お忙しいところ、ありがとうございます。")])

scenario("customer-service-keigo", "Keigo customer service", "敬語で接客する", 2, "2", "work",
    "You work at a hotel front desk and a guest has a complaint about noise.",
    "Hotel staff", "Guest, irritated but reasonable", "keigo",
    ["Apologise in keigo", "Ask for details", "Offer a solution"],
    ["騒音", "部屋", "変更", "申し訳ございません", "確認"],
    ["大変申し訳ございません", "詳しくお聞かせいただけますか", "お部屋を変更いたしましょうか"],
    [("隣の部屋がうるさくて眠れないんです。", "The next room is so noisy I can't sleep.", "Apologise", "大変申し訳ございません。"),
     ("昨日からずっとなんですよ。", "It's been like this since yesterday.", "Ask for details", "お部屋番号をお伺いしてもよろしいでしょうか。"),
     ("六一二号室です。", "Room 612.", "Offer a solution", "よろしければ、お部屋を変更いたしましょうか。"),
     ("変えてもらえるなら助かります。", "If you can change it, that would help.", "Explain the next step", "ただいま空いているお部屋を確認いたします。"),
     ("どのくらいかかりますか。", "How long will it take?", "Give a time", "五分ほどお待ちいただけますでしょうか。"),
     ("わかりました。", "All right.", "Close politely", "ご迷惑をおかけして、誠に申し訳ございません。")])

scenario("casual-chat", "Casual chat with a friend", "友達と雑談", 4, "1", "social",
    "You meet a friend for coffee on the weekend.",
    "Friend", "Close friend", "casual",
    ["Talk about your week", "Ask about theirs", "Make weekend plans"],
    ["週末", "最近", "忙しい", "疲れる", "遊ぶ"],
    ["最近どう？", "めっちゃ忙しかった", "週末何する？"],
    [("久しぶり！最近どう？", "Long time no see! How've you been?", "Talk about your week", "仕事がめっちゃ忙しかったよ。"),
     ("大変だね。疲れてない？", "That's rough. Aren't you tired?", "Answer", "ちょっと疲れてる。"),
     ("私も今週はテストがあった。", "I had exams this week too.", "Ask about it", "テストどうだった？"),
     ("まあまあかな。", "Not bad, I guess.", "Suggest plans", "週末、何か予定ある？"),
     ("特にないよ。", "Nothing in particular.", "Propose something", "じゃあ、映画でも見に行かない？"),
     ("いいね、行こう！", "Sounds good, let's go!", "Agree on time", "土曜日の午後はどう？")])

scenario("party-smalltalk", "Small talk at a party", "パーティーで雑談", 3, "1+", "social",
    "A welcome party for new staff. You meet someone from another department.",
    "Guest", "Colleague from another department", "polite",
    ["Introduce yourself", "Find a common topic", "End the chat gracefully"],
    ["部署", "出身", "趣味", "料理", "おいしい"],
    ["どちらの部署ですか", "私も…が好きです", "またお話ししましょう"],
    [("はじめまして。経理部の山田です。", "Nice to meet you. I'm Yamada from accounting.", "Introduce yourself", "はじめまして。営業部のジョーンズです。"),
     ("日本は長いんですか。", "Have you been in Japan long?", "Answer", "まだ半年です。"),
     ("週末は何をしているんですか。", "What do you do on weekends?", "Share a hobby", "よく山に登っています。"),
     ("私も登山が好きなんです！", "I love hiking too!", "Follow up", "おすすめの山はありますか。"),
     ("高尾山はいいですよ。", "Mt. Takao is nice.", "React", "今度行ってみます。"),
     ("あ、部長に呼ばれてしまいました。", "Oh, the manager is calling me.", "End gracefully", "またお話ししましょう。")])

scenario("short-report", "Presenting a short report", "短い報告をする", 2, "2+", "work",
    "A weekly team meeting. You report on project progress.",
    "Team member presenting", "Manager asking follow-up questions", "polite",
    ["Give the status", "Explain one problem and its cause", "Propose a next step", "Answer a follow-up question"],
    ["報告", "進捗", "問題", "原因", "対策", "予定"],
    ["進捗をご報告します", "問題が一つあります", "来週までに…する予定です"],
    [("では、進捗を報告してください。", "Please report on progress.", "Give the status", "進捗をご報告します。全体の七割が終わりました。"),
     ("順調ですか。", "Is it on track?", "Explain a problem", "一つ問題があります。テストが遅れています。"),
     ("原因は何ですか。", "What's the cause?", "Explain the cause", "担当者が一人休んでいるためです。"),
     ("対策はありますか。", "Do you have a countermeasure?", "Propose a next step", "来週から他のチームに手伝ってもらう予定です。"),
     ("締め切りには間に合いそうですか。", "Will you make the deadline?", "Answer", "はい、間に合う見込みです。"),
     ("わかりました。ありがとう。", "Understood. Thank you.", "Close", "以上です。")])

scenario("taxi", "Taking a taxi", "タクシーに乗る", 5, "0+", "travel",
    "You get into a taxi outside a hotel.",
    "Passenger", "Taxi driver", "polite",
    ["Say the destination", "Ask about time", "Pay"],
    ["空港", "時間", "かかる", "領収書", "止める"],
    ["…までお願いします", "どのくらいかかりますか", "ここで止めてください"],
    [("どちらまで？", "Where to?", "Say the destination", "空港までお願いします。"),
     ("はい。", "Okay.", "Ask how long", "どのくらいかかりますか。"),
     ("四十分ぐらいです。", "About forty minutes.", "Acknowledge", "わかりました。"),
     ("第一ターミナルですか。", "Terminal 1?", "Answer", "はい、第一ターミナルです。"),
     ("着きました。六千二百円です。", "We've arrived. 6,200 yen.", "Ask for a receipt", "領収書をお願いします。"),
     ("はい、どうぞ。", "Here you go.", "Thank", "ありがとうございました。")])


def main() -> None:
    out = Path(__file__).resolve().parent / "scenarios.json"
    doc = {
        "source": "llm",
        "note": "Scenarios and scripted turns drafted by an LLM; review with tools/items/review.py before marking verified.",
        "scenarios": S,
    }
    out.write_text(json.dumps(doc, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    print(f"{len(S)} scenarios")


if __name__ == "__main__":
    main()
