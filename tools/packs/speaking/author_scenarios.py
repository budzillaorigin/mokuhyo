"""Authoring source for tools/packs/speaking/scenarios.json (LLM-drafted, source="llm", awaiting human review).

Merge (re-runnable; never duplicates ids, keeps reviewed entries; see packs/practice_authoring.py):
    uv run python packs/speaking/author_scenarios.py
Draft more through an OpenAI-compatible endpoint (e.g. Ollama on the owner's GPU machine), appended to
batches/llm-drafts.json and merged:
    uv run python packs/speaking/author_scenarios.py draft --endpoint http://<lan-ip>:11434/v1 \\
        --model qwen2.5:14b --level 3 --category business --count 5

Sources: the scenarios below, then batches/*.json (same entry format as scenarios.json, documented in
docs/CONTENT_PACKS.md "Practice pack"). Each turn is (partner line ja, partner line en, what the learner should do,
one acceptable learner reply[, [other acceptable replies]]).
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import practice_authoring as pa

S = []


def scenario(id_, title_en, title_ja, jlpt, ilr, category, setting, learner, partner, register, goals, vocab, phrases, turns):
    S.append({
        "id": id_, "titleEn": title_en, "titleJa": title_ja, "jlpt": jlpt, "ilr": ilr, "category": category,
        "setting": setting, "learnerRole": learner, "partnerRole": partner, "register": register,
        "goals": goals, "vocabulary": vocab, "phrases": phrases,
        "turns": [
            {"partnerJa": t[0], "partnerEn": t[1], "intent": t[2], "sample": t[3], "accept": list(t[4]) if len(t) > 4 else []}
            for t in turns
        ],
    })


scenario("konbini", "At the convenience store", "コンビニで", 5, "0+", "daily",
    "A busy convenience store at lunchtime. You are buying a bento and a drink.",
    "Customer", "Store clerk, friendly and quick", "polite",
    ["Say whether you want the bento heated", "Ask for a bag or decline one", "Pay and say thank you"],
    ["弁当", "温める", "袋", "要る", "箸", "カード", "現金"],
    ["温めてください", "袋はいりません", "カードでお願いします", "ありがとうございます"],
    [("いらっしゃいませ。お弁当、温めますか。", "Welcome. Shall I heat up your bento?", "Answer yes or no about heating", "はい、お願いします。",
      ["温めてください。", "いいえ、そのままで大丈夫です。", "お願いします。"]),
     ("袋とお箸はご利用ですか。", "Would you like a bag and chopsticks?", "Answer about a bag and chopsticks", "袋はいりません。お箸は一つお願いします。",
      ["お箸だけお願いします。", "どちらも大丈夫です。", "両方お願いします。"]),
     ("お会計、八百五十円です。お支払いはどうされますか。", "That comes to 850 yen. How will you pay?", "Say how you will pay", "カードでお願いします。",
      ["現金でお願いします。", "カードで。", "現金で払います。"]),
     ("ありがとうございました。またお越しくださいませ。", "Thank you very much. Please come again.", "Thank the clerk", "ありがとうございます。",
      ["どうもありがとうございます。", "どうも。"])])

scenario("izakaya", "Ordering at an izakaya", "居酒屋で注文する", 4, "1", "daily",
    "A lively izakaya on a Friday evening. You and a friend have just arrived.",
    "Customer", "Energetic waiter", "polite",
    ["Order drinks", "Ask for a recommendation", "Order two dishes", "Ask for the bill"],
    ["生ビール", "注文", "おすすめ", "焼き鳥", "枝豆", "お会計", "メニュー"],
    ["とりあえず生二つ", "おすすめは何ですか", "これをください", "お会計をお願いします"],
    [("いらっしゃいませ！何名様ですか。", "Welcome! How many in your party?", "Say how many", "二人です。",
      ["二名です。", "二人でお願いします。", "二人ですが、席はありますか。"]),
     ("こちらのお席へどうぞ。お飲み物はお決まりですか。", "This way, please. Have you decided on drinks?", "Order drinks", "とりあえず生ビールを二つお願いします。",
      ["生二つください。", "生ビール一つとウーロン茶一つお願いします。", "生ビールを二つ。"]),
     ("かしこまりました。お料理はいかがなさいますか。", "Certainly. What would you like to eat?", "Ask for a recommendation", "おすすめは何ですか。",
      ["何がおすすめですか。", "今日のおすすめはありますか。", "人気のメニューは何ですか。"]),
     ("今日は焼き鳥がおすすめです。枝豆もすぐお出しできますよ。", "Today the yakitori is our recommendation. We can bring edamame right away, too.", "Order two dishes", "じゃあ、焼き鳥と枝豆をください。",
      ["焼き鳥の盛り合わせと枝豆をお願いします。", "焼き鳥をお願いします。それと枝豆も。", "じゃあ、両方お願いします。"]),
     ("焼き鳥は塩とたれ、どちらになさいますか。", "Salt or sauce for the yakitori?", "Choose one", "たれでお願いします。",
      ["塩でお願いします。", "半分ずつにできますか。", "たれで。"]),
     ("（しばらくして）お飲み物のおかわりはいかがですか。", "(A while later) Would you like another drink?", "Decline and ask for the bill", "いいえ、大丈夫です。お会計をお願いします。",
      ["お会計お願いします。", "もう大丈夫です。お勘定をお願いします。", "結構です。お会計、いいですか。"]),
     ("四千二百円になります。ありがとうございました！またお越しくださいませ！", "That's 4,200 yen. Thank you! Please come again!", "Pay and thank", "ごちそうさまでした。",
      ["カードでお願いします。ごちそうさまでした。", "おいしかったです。ありがとうございました。", "現金でお願いします。"])])

scenario("doctor", "At the doctor's", "病院で", 4, "1", "health",
    "A small clinic. You have had a fever and a sore throat since yesterday.",
    "Patient", "Calm, careful doctor", "polite",
    ["Describe your symptoms", "Say since when", "Answer about allergies", "Ask how to take the medicine"],
    ["熱", "喉", "痛い", "咳", "薬", "アレルギー", "昨日"],
    ["昨日から熱があります", "喉が痛いです", "アレルギーはありません", "一日何回飲みますか"],
    [("今日はどうしましたか。", "What brings you in today?", "Describe symptoms", "熱があって、喉が痛いです。",
      ["喉が痛くて、熱もあります。", "風邪をひいたみたいです。", "昨日から熱があります。"]),
     ("いつからですか。", "Since when?", "Say since when", "昨日の夜からです。",
      ["昨日からです。", "昨日の夕方ごろからです。", "一日ぐらい前からです。"]),
     ("熱は何度ありますか。", "How high is your fever?", "Give your temperature", "今朝は三十八度でした。",
      ["三十八度ぐらいです。", "三十七度五分です。", "測っていませんが、体が熱いです。"]),
     ("咳や鼻水は出ますか。", "Any cough or runny nose?", "Answer", "咳が少し出ます。",
      ["咳は少し出ますが、鼻水は出ません。", "いいえ、出ません。", "鼻水が少し出ます。"]),
     ("ちょっと喉を見せてください。…赤くなっていますね。薬のアレルギーはありますか。", "Let me see your throat. ... It's red. Any allergies to medicine?", "Answer about allergies", "いいえ、ありません。",
      ["特にありません。", "ペニシリンのアレルギーがあります。", "ないと思います。"]),
     ("わかりました。風邪ですね。三日分の薬を出しておきます。", "All right. It's a cold. I'll prescribe three days of medicine.", "Ask how to take it", "一日何回飲みますか。",
      ["どうやって飲めばいいですか。", "いつ飲めばいいですか。", "飲み方を教えてください。"]),
     ("一日三回、食後に飲んでください。熱が下がらなかったら、またいらしてください。", "Three times a day after meals. If the fever doesn't come down, please come back.", "Ask a follow-up question (work, bath, recovery)", "明日、仕事に行ってもいいですか。",
      ["お風呂に入ってもいいですか。", "学校は休んだほうがいいですか。", "何日ぐらいで治りますか。"]),
     ("熱があるうちは、無理をしないで休んで、水をよく飲んでください。お大事に。", "While you have a fever, don't push yourself: rest and drink plenty of water. Take care.", "Thank and leave", "わかりました。ありがとうございました。",
      ["ありがとうございました。", "はい、そうします。失礼します。", "わかりました。休みます。"])])

scenario("pharmacy", "At the pharmacy", "薬局で", 4, "1", "health",
    "A drugstore. You need something for a headache and want to check it is safe with your allergy.",
    "Customer", "Pharmacist", "polite",
    ["Explain what you need", "Mention an allergy", "Ask about side effects"],
    ["頭痛", "薬", "眠い", "副作用", "アレルギー", "飲む"],
    ["頭痛の薬はありますか", "眠くなりますか", "これを飲んでも大丈夫ですか"],
    [("何かお探しですか。", "Can I help you find something?", "Say what you need", "頭痛の薬はありますか。",
      ["頭が痛いので、薬がほしいんですが。", "頭痛薬を探しています。", "頭痛に効く薬はどれですか。"]),
     ("頭痛薬でしたら、こちらはいかがでしょう。", "For headaches, how about this one?", "Mention an allergy", "アスピリンのアレルギーがあるんですが、大丈夫ですか。",
      ["アスピリンが飲めないんです。", "アレルギーがあるんですが、これは大丈夫ですか。", "私はアスピリンのアレルギーがあります。"]),
     ("それでしたら、こちらはアスピリンが入っていないので安心です。ほかにお薬は飲んでいますか。", "In that case this one has no aspirin, so it's safe. Are you taking any other medicine?", "Answer about other medicine", "いいえ、ほかには飲んでいません。",
      ["いいえ、何も飲んでいません。", "花粉症の薬を飲んでいます。", "今は飲んでいません。"]),
     ("わかりました。それなら、こちらで問題ありません。", "All right. Then this one is fine.", "Ask about side effects", "眠くなりますか。",
      ["副作用はありますか。", "飲むと眠くなりますか。", "何か副作用はありますか。"]),
     ("少し眠くなることがあります。飲んだあとは運転しないでください。", "It may make you a little sleepy. Please don't drive after taking it.", "Acknowledge and ask how to take it", "わかりました。一回に何錠飲みますか。",
      ["一日何回飲めばいいですか。", "わかりました。飲み方を教えてください。", "運転はしません。どうやって飲みますか。"]),
     ("一回二錠で、四時間以上あけて、一日三回までです。", "Two tablets per dose, at least four hours apart, up to three times a day.", "Decide to buy it", "わかりました。これをください。",
      ["じゃあ、これにします。", "これをお願いします。", "わかりました。一つください。"]),
     ("ありがとうございます。九百八十円です。お大事になさってください。", "Thank you. That's 980 yen. Please take care.", "Pay and thank", "ありがとうございます。",
      ["カードでお願いします。", "はい、ありがとうございました。", "現金でお願いします。"])])

scenario("station-lost", "Lost item at the station", "駅で忘れ物", 4, "1", "travel",
    "A station office. You left your umbrella on the train twenty minutes ago.",
    "Passenger", "Station staff member", "polite",
    ["Explain what you lost", "Describe it", "Say which train and when", "Leave contact details"],
    ["忘れ物", "傘", "電車", "色", "連絡", "電話番号"],
    ["電車に傘を忘れました", "青い傘です", "電話番号は…です"],
    [("どうされましたか。", "What happened?", "Explain the problem", "電車に傘を忘れてしまいました。",
      ["忘れ物をしてしまったんですが。", "電車の中に傘を置いてきてしまいました。", "傘を忘れました。"]),
     ("どの電車ですか。", "Which train?", "Say which train and when", "二十分前の東京行きです。",
      ["東京行きの快速です。二十分ぐらい前に降りました。", "十時五分に着いた電車です。", "二十分ぐらい前の電車です。"]),
     ("何両目に乗っていたか、覚えていますか。", "Do you remember which car you were in?", "Answer (or say you don't know)", "たぶん三両目です。",
      ["前のほうだったと思います。", "すみません、覚えていません。", "三両目です。"]),
     ("どんな傘ですか。", "What does the umbrella look like?", "Describe it", "青くて、長い傘です。",
      ["青い長い傘です。", "紺色の大きい傘です。持つところが木です。", "青い傘です。"]),
     ("今、終点の駅に問い合わせてみますね。…まだ見つかっていないそうです。", "I'll check with the terminal station now. ... They say it hasn't been found yet.", "Ask what happens next", "そうですか。見つかったら、連絡してもらえますか。",
      ["どうすればいいですか。", "あとでまた来たほうがいいですか。", "見つかったら教えてください。"]),
     ("見つかったらご連絡しますので、お名前と電話番号をお願いします。", "We'll contact you if it's found, so your name and phone number, please.", "Give contact details", "スミスです。電話番号は〇九〇・一二三四・五六七八です。",
      ["スミスと申します。番号は〇九〇・一二三四・五六七八です。", "名前はスミスです。電話番号はここに書きます。", "スミスです。携帯の番号を書きます。"]),
     ("ありがとうございます。見つかりしだい、ご連絡いたします。", "Thank you. We'll contact you as soon as it turns up.", "Thank and close", "よろしくお願いします。",
      ["お手数をおかけします。", "ありがとうございます。よろしくお願いします。", "助かります。"])])

scenario("directions", "Asking for directions", "道を聞く", 5, "0+", "travel",
    "A street corner near a station. You are looking for the post office.",
    "Tourist", "Kind passer-by", "polite",
    ["Get someone's attention politely", "Ask where the place is", "Confirm the directions"],
    ["郵便局", "道", "まっすぐ", "右", "左", "信号", "近く"],
    ["すみません", "郵便局はどこですか", "まっすぐですね"],
    [("はい、何でしょう。", "Yes, can I help?", "Ask where the post office is", "すみません、郵便局はどこですか。",
      ["郵便局を探しているんですが。", "この近くに郵便局はありますか。", "郵便局へ行きたいんですが。"]),
     ("郵便局ですね。この道をまっすぐ行って、二つ目の信号を右に曲がってください。", "The post office? Go straight along this road and turn right at the second traffic light.", "Repeat the directions to confirm", "二つ目の信号を右ですね。",
      ["まっすぐ行って、右ですね。", "二つ目の信号を右に曲がるんですね。", "右ですね。"]),
     ("そうです。曲がると、郵便局は左にあります。歩いて五分ぐらいですよ。", "That's right. After you turn, it's on the left. About five minutes on foot.", "Thank them", "どうもありがとうございます。",
      ["ありがとうございます。助かりました。", "左ですね。ありがとうございます。", "近いですね。ありがとうございます。"]),
     ("いいえ。気をつけて行ってくださいね。", "Not at all. Take care getting there.", "Say goodbye", "はい、失礼します。",
      ["ありがとうございました。", "失礼します。", "はい、ありがとうございます。"])])

scenario("hotel", "Hotel check-in", "ホテルのチェックイン", 4, "1", "travel",
    "A business hotel front desk in the evening. You are arriving for a two-night stay.",
    "Guest with a reservation", "Front desk clerk", "keigo",
    ["Say you have a reservation", "Confirm dates", "Ask about breakfast", "Ask for Wi-Fi"],
    ["予約", "チェックイン", "朝食", "部屋", "鍵", "パスワード"],
    ["予約している…です", "朝食は何時からですか", "Wi-Fiのパスワードを教えてください"],
    [("いらっしゃいませ。チェックインでございますか。", "Welcome. Are you checking in?", "Say you have a reservation", "はい、予約しているブラウンです。",
      ["はい。ブラウンという名前で予約しています。", "チェックインをお願いします。ブラウンです。", "予約したブラウンです。"]),
     ("ブラウン様でございますね。本日から二泊、シングルルームでお取りしております。", "Mr./Ms. Brown. We have you in a single room for two nights from tonight.", "Confirm the dates", "はい、そうです。",
      ["はい、二泊でお願いします。", "はい、間違いありません。", "はい、それでお願いします。"]),
     ("恐れ入りますが、パスポートを拝見してもよろしいでしょうか。", "Excuse me, may I see your passport?", "Hand it over", "はい、どうぞ。",
      ["はい、こちらです。", "はい、お願いします。", "これです。"]),
     ("ありがとうございます。こちらにご住所とお名前のご記入をお願いいたします。", "Thank you. Please fill in your address and name here.", "Acknowledge and ask about breakfast", "はい。朝食は何時からですか。",
      ["わかりました。朝ご飯は何時からですか。", "はい。朝食はどこで食べられますか。", "書きました。朝食は付いていますか。"]),
     ("ご朝食は一階のレストランで、七時から九時半まででございます。", "Breakfast is in the ground-floor restaurant from 7:00 to 9:30.", "Ask for the Wi-Fi password", "Wi-Fiのパスワードを教えてください。",
      ["Wi-Fiは使えますか。", "インターネットのパスワードはありますか。", "Wi-Fiのパスワードは何ですか。"]),
     ("Wi-Fiのパスワードは、こちらのカードに書いてございます。", "The Wi-Fi password is written on this card.", "Ask about check-out time", "チェックアウトは何時ですか。",
      ["チェックアウトの時間を教えてください。", "最後の日は何時までに出ればいいですか。", "ありがとうございます。チェックアウトは何時までですか。"]),
     ("チェックアウトは十一時でございます。こちらがお部屋の鍵で、八〇五号室でございます。", "Check-out is at 11:00. Here is your key; you're in room 805.", "Take the key and thank", "ありがとうございます。",
      ["八〇五号室ですね。ありがとうございます。", "わかりました。どうも。", "はい、ありがとうございます。"]),
     ("エレベーターは右手にございます。どうぞごゆっくりお過ごしくださいませ。", "The elevator is on your right. Please enjoy your stay.", "Respond politely as an arriving guest", "ありがとうございます。よろしくお願いします。",
      ["お世話になります。", "よろしくお願いします。", "ありがとうございます。"])])

scenario("phone-reschedule", "Rescheduling by phone", "電話で予定を変える", 3, "1+", "work",
    "You call a dental clinic to move tomorrow's appointment.",
    "Patient calling", "Receptionist", "polite",
    ["Identify yourself", "Explain you can't come", "Propose another day", "Confirm the new time"],
    ["予約", "変更", "都合", "明日", "来週", "空く"],
    ["明日の予約を変更したいんですが", "都合が悪くなってしまって", "来週の火曜日は空いていますか"],
    [("はい、さくら歯科でございます。", "Hello, Sakura Dental.", "Identify yourself and say why you call", "もしもし、明日十時に予約しているキムです。予約を変更したいんですが。",
      ["明日の十時に予約しているキムですが、予約を変更できますか。", "キムと申します。明日の予約を変えたいんですが。", "明日の予約を変更したいんですが。"]),
     ("キム様ですね。かしこまりました。ご希望の日はありますか。", "Ms./Mr. Kim. Certainly. Is there a day you'd prefer?", "Propose another day", "来週の火曜日は空いていますか。",
      ["来週の火曜日か水曜日はどうですか。", "来週の午後なら、いつでも大丈夫です。", "来週の火曜日はどうでしょうか。"]),
     ("来週ですと、火曜日の午後三時か、木曜日の午前十時が空いております。", "Next week we have Tuesday at 3 p.m. or Thursday at 10 a.m.", "Choose a time", "火曜日の三時でお願いします。",
      ["木曜日の十時でお願いします。", "では、火曜日の午後三時にします。", "火曜日の三時で大丈夫です。"]),
     ("では、その時間でお取りしておきます。", "Then I'll book you for that time.", "Confirm the new time", "来週火曜日の三時ですね。よろしくお願いします。",
      ["はい、よろしくお願いします。", "来週ですね。わかりました。", "火曜日の三時ですね。"]),
     ("はい。明日のご予約はキャンセルしておきますね。", "Yes. I'll cancel tomorrow's booking.", "Apologise for the trouble", "ご迷惑をおかけしてすみません。",
      ["急に変更して申し訳ありません。", "すみません、お願いします。", "お手数をおかけします。"]),
     ("いいえ。その後、歯の痛みはいかがですか。", "Not at all. How has the tooth pain been since your last visit?", "Answer", "今はあまり痛くないです。",
      ["大丈夫です。", "まだ少し痛いです。", "だいぶよくなりました。"]),
     ("そうですか。何かあれば、いつでもお電話ください。お大事になさってください。", "I see. Call any time if anything comes up. Take care.", "Close the call", "ありがとうございます。失礼します。",
      ["はい、失礼します。", "よろしくお願いします。失礼します。", "ありがとうございました。"])])

scenario("job-interview", "Job interview", "面接", 3, "2", "work",
    "A formal interview for a part-time office job.",
    "Applicant", "Interviewer, polite but probing", "keigo",
    ["Introduce yourself formally", "Explain your motivation", "Describe a strength", "Ask one question at the end"],
    ["志望", "動機", "経験", "長所", "貢献", "質問"],
    ["よろしくお願いいたします", "御社を志望した理由は", "私の長所は"],
    [("本日はお越しいただき、ありがとうございます。では、自己紹介をお願いします。", "Thank you for coming today. Please introduce yourself.", "Introduce yourself formally", "マリア・ロペスと申します。本日はよろしくお願いいたします。",
      ["はじめまして、マリア・ロペスと申します。スペインから参りました。よろしくお願いいたします。", "ロペスと申します。よろしくお願いいたします。", "マリア・ロペスと申します。大学で経済を勉強しております。"]),
     ("志望動機を教えてください。", "Tell us why you applied.", "Explain motivation", "日本語を使って仕事をしたいと思い、応募いたしました。",
      ["御社の国際的なお仕事に興味があり、志望いたしました。", "日本語を生かせる仕事がしたいと思いました。", "事務の経験を生かしたいと思い、応募しました。"]),
     ("これまでにどのようなご経験がありますか。", "What experience do you have?", "Describe experience", "大学で事務のアルバイトを二年間しておりました。",
      ["レストランで三年間アルバイトをしておりました。", "事務の経験はありませんが、接客の経験がございます。", "前の会社で一年間、データ入力を担当しておりました。"]),
     ("そのお仕事で大変だったことは何ですか。", "What was difficult in that work?", "Describe a difficulty and how you handled it", "忙しい時期にミスが増えたので、確認の手順を作りました。",
      ["最初は敬語が難しかったのですが、先輩に教えていただいて慣れました。", "同時にたくさんの仕事をすることが大変でした。", "お客様からのクレームへの対応が大変でした。"]),
     ("あなたの長所は何ですか。", "What is your strength?", "Describe a strength", "私の長所は責任感が強いところです。",
      ["最後まであきらめないところです。", "私の長所は、誰とでもすぐに仲良くなれるところです。", "責任感が強いとよく言われます。"]),
     ("では、短所はいかがですか。", "And your weakness?", "Name a weakness and how you work on it", "心配性なところです。ですが、そのおかげで確認は丁寧にしております。",
      ["少し慎重すぎるところです。今は早く決めるように気をつけております。", "人に頼むのが苦手なところです。", "緊張しやすいところです。"]),
     ("日本語で電話の対応をすることもありますが、大丈夫ですか。", "You may also handle phone calls in Japanese. Is that all right?", "Answer honestly", "はい、大丈夫です。まだ勉強中ですが、精一杯頑張ります。",
      ["はい、以前も電話対応をしておりました。", "少し不安ですが、頑張ります。", "はい、問題ございません。"]),
     ("週に何日ぐらい勤務できますか。", "About how many days a week can you work?", "Give your availability", "週に三日、月曜日と水曜日と金曜日なら勤務できます。",
      ["週三日です。", "授業がない日なら、週四日勤務できます。", "週に三日から四日でお願いいたします。"]),
     ("いつから勤務可能ですか。", "When could you start?", "Answer", "来月から勤務可能です。",
      ["来月の一日から働けます。", "すぐにでも大丈夫です。", "来週からでも可能です。"]),
     ("最後に、何かご質問はありますか。", "Finally, do you have any questions?", "Ask one question", "研修はどのように行われますか。",
      ["入社後の研修について教えていただけますか。", "一日の仕事の流れを教えていただけますか。", "職場には外国人のスタッフもいらっしゃいますか。"]),
     ("ご質問ありがとうございます。入社後は先輩が丁寧にご説明しますので、ご安心ください。結果は一週間以内にご連絡いたします。本日はありがとうございました。", "Thank you for the question. After joining, a senior colleague will explain everything carefully, so don't worry. We'll contact you with the result within a week. Thank you for coming today.", "Thank the interviewer and close formally", "本日はお時間をいただき、ありがとうございました。よろしくお願いいたします。",
      ["ありがとうございました。失礼いたします。", "本日はありがとうございました。ご連絡をお待ちしております。", "どうぞよろしくお願いいたします。"])])

scenario("self-intro-work", "First day at work", "職場での自己紹介", 4, "1", "work",
    "Your first morning in a Japanese office. Your manager introduces you to the team.",
    "New employee", "Manager", "polite",
    ["Introduce yourself", "Say where you're from and your role", "Ask for guidance politely"],
    ["自己紹介", "出身", "担当", "同僚", "よろしく"],
    ["はじめまして", "…から来ました", "ご指導よろしくお願いします"],
    [("じゃあ、みんなに自己紹介してください。", "Please introduce yourself to everyone.", "Introduce yourself", "はじめまして、アレックスと申します。本日からお世話になります。",
      ["はじめまして、アレックスです。よろしくお願いします。", "アレックスと申します。どうぞよろしくお願いいたします。", "今日からこちらで働くアレックスです。"]),
     ("出身はどちらですか。", "Where are you from?", "Say where you are from", "アメリカのシアトルから来ました。",
      ["シアトル出身です。", "アメリカです。シアトルという町です。", "アメリカのシアトルです。"]),
     ("こちらでは営業を担当してもらいます。", "Here you'll be in charge of sales.", "Respond with enthusiasm", "精一杯がんばります。",
      ["早く仕事を覚えられるようにがんばります。", "営業は初めてですが、がんばります。", "はい、よろしくお願いします。"]),
     ("日本の会社は初めてですか。", "Is this your first Japanese company?", "Answer", "はい、初めてです。",
      ["はい、日本の会社で働くのは初めてです。", "いいえ、前に東京で一年働きました。", "初めてなので、少し緊張しています。"]),
     ("何かわからないことがあれば、隣の田中さんに聞いてください。", "If there's anything you don't understand, ask Tanaka next to you.", "Greet Tanaka", "田中さん、よろしくお願いします。",
      ["田中さん、いろいろ教えてください。", "田中さん、よろしくお願いいたします。", "田中さん、お世話になります。"]),
     ("では、今日からよろしく。一緒にがんばりましょう。", "Well then, welcome aboard. Let's work hard together.", "Close politely", "ご指導よろしくお願いいたします。",
      ["はい、よろしくお願いします。", "がんばります。ありがとうございます。", "いろいろ教えてください。よろしくお願いします。"])])

scenario("counterpart-briefing", "Briefing a Japanese counterpart", "日本側担当者への説明", 2, "2+", "official",
    "A meeting room on base. You brief a Japanese liaison officer about tomorrow's joint schedule.",
    "Briefing officer", "Japanese liaison officer, formal and precise", "keigo",
    ["State the purpose of the briefing", "Explain the schedule clearly", "Confirm points of contact", "Check for questions"],
    ["日程", "訓練", "集合", "担当者", "確認", "連絡"],
    ["本日は…についてご説明いたします", "集合時間は…です", "ご質問はございますか"],
    [("本日はよろしくお願いいたします。", "Thank you for your time today.", "Greet and state the purpose", "よろしくお願いいたします。本日は明日の合同訓練の日程についてご説明いたします。",
      ["本日は明日の合同訓練についてご説明いたします。", "お時間をいただき、ありがとうございます。明日の日程をご説明いたします。", "明日の合同訓練の日程をご説明いたします。"]),
     ("お願いいたします。まず、集合は何時でしょうか。", "Please go ahead. First, what time do we assemble?", "Give time and place", "集合は午前七時、第二格納庫前です。",
      ["午前七時に第二格納庫前へ集合をお願いいたします。", "七時集合で、場所は第二格納庫の前です。", "集合時間は午前七時、場所は第二格納庫前でございます。"]),
     ("第二格納庫ですね。日本側の車両はどこに止めればよろしいでしょうか。", "Hangar 2. Where should the Japanese side park their vehicles?", "Explain parking", "格納庫の北側の駐車場をご利用ください。",
      ["北側の駐車場に止めていただけます。", "駐車場は格納庫の北側にございます。", "格納庫の隣の駐車場をお使いください。"]),
     ("承知いたしました。訓練の開始と終了の時間を教えていただけますか。", "Understood. Could you give me the start and end times of the training?", "Explain the schedule", "八時に開始し、十六時に終了する予定です。",
      ["訓練は午前八時から午後四時までを予定しております。", "開始は八時、終了は十六時の予定です。", "八時開始、十六時終了でございます。"]),
     ("昼食はどのようになりますか。", "What are the arrangements for lunch?", "Explain lunch arrangements", "昼食は十二時から、食堂でご用意いたします。",
      ["十二時から一時間、食堂で昼食をとっていただきます。", "昼食はこちらで準備いたします。", "恐れ入りますが、昼食は各自でお願いいたします。"]),
     ("そちら側の参加人数はどのくらいでしょうか。", "How many personnel will take part from your side?", "Answer with numbers", "こちらからは二十名が参加いたします。",
      ["二十名を予定しております。", "二十名前後の予定です。", "私どもからは二十名でございます。"]),
     ("通訳は付きますか。", "Will there be an interpreter?", "Answer", "はい、通訳を一名手配しております。",
      ["はい、通訳が一名同行いたします。", "申し訳ございませんが、通訳はおりません。", "はい、通訳を二名用意しております。"]),
     ("雨天の場合はどうなりますか。", "What happens if it rains?", "Explain the contingency", "雨天の場合は屋内訓練に変更いたします。",
      ["雨の場合は、格納庫の中で実施いたします。", "天候が悪い場合は、屋内の訓練に切り替えます。", "雨天時は屋内訓練となります。"]),
     ("変更がある場合、いつまでにご連絡いただけますか。", "If there's a change, by when will you let us know?", "Say when you will notify", "当日の午前五時までにご連絡いたします。",
      ["前日の二十時までにお知らせいたします。", "朝五時までに電話でご連絡いたします。", "決まり次第、すぐにご連絡いたします。"]),
     ("連絡窓口はどなたになりますか。", "Who is the point of contact?", "Name the point of contact", "連絡窓口は私、ジョンソン大尉が担当いたします。",
      ["私、ジョンソンが担当いたします。", "窓口はジョンソン大尉です。こちらが連絡先でございます。", "私が担当させていただきます。"]),
     ("承知いたしました。大変わかりやすいご説明でした。", "Understood. That was a very clear explanation.", "Thank them and check for questions", "ありがとうございます。ほかにご質問はございますか。",
      ["何かご不明な点はございますか。", "ご質問はございませんか。", "ほかに確認なさりたい点はございますか。"]),
     ("いえ、特にございません。では、明日はよろしくお願いいたします。", "No, nothing in particular. We look forward to working with you tomorrow.", "Close the briefing", "ありがとうございました。明日よろしくお願いいたします。",
      ["本日はありがとうございました。", "お忙しいところありがとうございました。明日もよろしくお願いいたします。", "では、明日七時にお待ちしております。"])])

scenario("base-gate", "Visitor check-in at the base gate", "基地ゲートでの受付", 3, "1+", "official",
    "The main gate of a base. A Japanese contractor arrives for a meeting; you are on gate duty.",
    "Gate guard", "Visiting contractor", "polite",
    ["Greet and ask the purpose of the visit", "Request ID", "Confirm the appointment", "Explain the visitor pass rules"],
    ["身分証明書", "訪問", "目的", "許可", "記入", "返却"],
    ["ご用件は何ですか", "身分証明書を見せてください", "お帰りの際に返却してください"],
    [("おはようございます。十時から会議があって来ました。", "Good morning. I'm here for a 10 o'clock meeting.", "Ask who they are meeting", "おはようございます。どなたとの会議ですか。",
      ["おはようございます。どなたにお会いになりますか。", "どちらの部署との会議ですか。", "担当の方のお名前を教えてください。"]),
     ("施設課の佐藤さんです。", "With Mr. Sato from facilities.", "Request ID", "身分証明書を見せていただけますか。",
      ["身分証明書をお願いします。", "身分証明書はお持ちですか。", "わかりました。身分証明書を拝見できますか。"]),
     ("はい、運転免許証です。", "Yes, here is my driver's license.", "Ask them to fill out a form", "ありがとうございます。こちらの用紙に記入してください。",
      ["こちらに名前と会社名を記入してください。", "では、この用紙に記入をお願いします。", "確認しました。こちらにご記入ください。"]),
     ("これでいいですか。", "Is this all right?", "Check it and ask them to wait while you call", "はい、大丈夫です。今、佐藤さんに確認しますので、少々お待ちください。",
      ["ありがとうございます。少しお待ちください。", "はい。担当者に連絡しますので、お待ちください。", "大丈夫です。確認しますので、少々お待ちください。"]),
     ("はい。…あのう、車の中にパソコンがあるんですが、持って入ってもいいですか。", "Sure. ... Um, I have a laptop in the car. May I bring it in?", "Explain the rule on laptops", "パソコンは登録が必要です。この用紙にも書いてください。",
      ["パソコンの持ち込みには登録が必要です。", "パソコンは登録すれば持って入れます。", "申し訳ありませんが、パソコンは車に置いてきてください。"]),
     ("わかりました、そうします。", "All right, I'll do that.", "Confirm the appointment and issue a pass", "佐藤さんに確認が取れました。こちらが通行証です。",
      ["確認できました。これが通行証です。", "お待たせしました。こちらの通行証を付けてください。", "佐藤さんがお待ちです。通行証をどうぞ。"]),
     ("ありがとうございます。施設課はどこに行けばいいですか。", "Thank you. Where do I go for facilities?", "Give directions", "まっすぐ行って、二つ目の建物です。",
      ["この道をまっすぐ行って、右側の二つ目の建物です。", "二つ目の建物の二階です。", "まっすぐ行くと、左に見えます。"]),
     ("わかりました。どうも。", "Got it. Thanks.", "Explain the pass rules and close", "お帰りの際に、通行証を返却してください。",
      ["通行証はいつも見えるところに付けてください。帰る時にここで返してください。", "帰る時は、ここで通行証を返却してください。", "どうぞ。お帰りの際は、こちらに通行証を返してください。"])])

scenario("koban", "At the police box", "交番で", 4, "1", "official",
    "A koban. You found a wallet on the street and want to hand it in.",
    "Finder", "Police officer", "polite",
    ["Say what you found", "Say where and when", "Give your contact details"],
    ["財布", "拾う", "交番", "場所", "届ける", "連絡先"],
    ["財布を拾いました", "駅の前で拾いました", "連絡先は…です"],
    [("どうしましたか。", "What's the matter?", "Say what you found", "財布を拾ったので、届けに来ました。",
      ["財布を拾いました。", "道で財布を見つけたんですが。", "落とし物を届けに来ました。"]),
     ("それはありがとうございます。どこで拾いましたか。", "Thank you for that. Where did you find it?", "Say where", "駅の前の道で拾いました。",
      ["駅前のコンビニの近くです。", "この先の交差点で拾いました。", "駅の前です。"]),
     ("何時ごろですか。", "About what time?", "Say when", "十分ぐらい前です。",
      ["さっきです。", "三時ごろです。", "十五分ぐらい前だと思います。"]),
     ("中は見ましたか。", "Did you look inside?", "Answer", "いいえ、見ていません。",
      ["いいえ、開けていません。", "名前がないか、少しだけ見ました。", "見ていません。"]),
     ("では、一緒に中身を確認しますね。…現金が一万二千円と、カードが三枚入っています。", "Then let's check the contents together. ... 12,000 yen in cash and three cards.", "Acknowledge", "はい、わかりました。",
      ["そうですか。", "はい、確認しました。", "はい。"]),
     ("落とし主が見つかったら、お礼を受け取る権利がありますが、どうしますか。", "If the owner is found, you have the right to a reward. What would you like to do?", "Say whether you want a reward", "お礼はいりません。",
      ["権利は放棄します。", "受け取ります。", "いりません。落とし主に返してあげてください。"]),
     ("わかりました。では書類を作りますので、お名前と連絡先をお願いします。", "Understood. I'll make out the paperwork, so your name and contact details, please.", "Give contact details", "リーです。電話番号は〇八〇・九八七六・五四三二です。",
      ["リーと申します。電話番号をここに書きます。", "名前はリーです。連絡先はこの番号です。", "リーです。番号はこれです。"]),
     ("ご協力ありがとうございました。気をつけてお帰りください。", "Thank you for your cooperation. Get home safely.", "Respond and leave", "いいえ、よろしくお願いします。",
      ["よろしくお願いします。失礼します。", "持ち主が見つかるといいですね。", "ありがとうございます。失礼します。"])])

scenario("bank", "Opening a bank account", "銀行で口座を作る", 3, "1+", "daily",
    "A bank branch. You want to open an account for your salary.",
    "Customer", "Bank clerk", "keigo",
    ["Say what you want to do", "Show residence card", "Answer questions about purpose", "Ask about the cash card"],
    ["口座", "開く", "在留カード", "印鑑", "目的", "給料", "通帳", "暗証番号"],
    ["口座を作りたいんですが", "在留カードはこちらです", "キャッシュカードはいつ届きますか"],
    [("本日はどのようなご用件でしょうか。", "How may I help you today?", "Say what you want", "口座を開きたいんですが。",
      ["口座を作りたいんですが。", "新しく口座を開きたいです。", "普通預金の口座を作りたいんですが。"]),
     ("かしこまりました。身分証明書はお持ちですか。", "Certainly. Do you have identification?", "Show residence card", "はい、在留カードです。",
      ["在留カードでいいですか。", "はい、在留カードはこちらです。", "はい、パスポートと在留カードがあります。"]),
     ("拝見いたします。…日本にいらしてから、どのくらいになりますか。", "Let me see. ... How long have you been in Japan?", "Answer", "三か月です。",
      ["去年の十月に来ました。", "半年ぐらいです。", "もうすぐ一年です。"]),
     ("ありがとうございます。口座のご利用目的は何でしょうか。", "Thank you. What will the account be used for?", "Explain purpose", "給料の振り込みに使います。",
      ["給料を受け取るためです。", "会社から、給料の口座を作るように言われました。", "生活費と給料の振り込みです。"]),
     ("お勤め先を確認できるものはございますか。", "Do you have anything that shows where you work?", "Show proof of employment", "はい、会社の社員証があります。",
      ["雇用契約書を持ってきました。", "社員証でいいですか。", "はい、こちらです。"]),
     ("ありがとうございます。印鑑はお持ちですか。", "Thank you. Do you have a personal seal?", "Answer (or ask if a signature is okay)", "いいえ、サインでもいいですか。",
      ["印鑑は持っていません。", "はい、持ってきました。", "サインでも大丈夫ですか。"]),
     ("当行では、印鑑でもサインでもお作りいただけます。では、こちらの申込書にご記入ください。", "At our bank you can open it with either a seal or a signature. Please fill in this application form.", "Ask for help with the form", "すみません、ここは何を書けばいいですか。",
      ["はい、わかりました。", "ローマ字で書いてもいいですか。", "書き方を教えていただけますか。"]),
     ("こちらにはお電話番号をお願いいたします。それから、キャッシュカードの暗証番号を四けたでお決めください。", "Please put your phone number here. And please choose a four-digit PIN for the cash card.", "Confirm", "四けたですね。わかりました。",
      ["わかりました。書きます。", "数字だけですね。", "はい、決めました。"]),
     ("ありがとうございます。通帳は本日お渡しいたします。", "Thank you. We'll give you your passbook today.", "Ask about the cash card", "キャッシュカードはいつ届きますか。",
      ["カードはいつもらえますか。", "キャッシュカードも今日もらえますか。", "カードはどうやって受け取りますか。"]),
     ("キャッシュカードは一週間ほどで、ご自宅に郵送いたします。本日はありがとうございました。", "We'll mail the cash card to your home in about a week. Thank you for coming in today.", "Thank and close", "わかりました。ありがとうございました。",
      ["一週間ですね。ありがとうございます。", "ありがとうございました。よろしくお願いします。", "わかりました。お世話になりました。"])])

scenario("post-office", "Sending a package", "郵便局で荷物を送る", 4, "1", "daily",
    "A post office counter. You want to send a small package to the United States.",
    "Customer", "Postal clerk", "polite",
    ["Say where you're sending it", "Choose a shipping method", "Say what's inside"],
    ["荷物", "送る", "航空便", "船便", "中身", "届く"],
    ["アメリカに送りたいです", "航空便でお願いします", "中身は本です"],
    [("こちらの荷物はどちらへ送りますか。", "Where are you sending this package?", "Say the destination", "アメリカに送りたいです。",
      ["アメリカまでお願いします。", "アメリカのニューヨークです。", "アメリカです。"]),
     ("では、重さを量りますね。…一・五キロです。航空便と船便、どちらにしますか。", "Let me weigh it. ... 1.5 kg. Airmail or sea mail?", "Ask about delivery times", "航空便だと何日ぐらいかかりますか。",
      ["どちらが早いですか。", "それぞれ何日ぐらいかかりますか。", "船便はどのくらいかかりますか。"]),
     ("航空便なら一週間ぐらい、船便なら二か月ぐらいです。料金は航空便のほうが高いです。", "Airmail takes about a week, sea mail about two months. Airmail costs more.", "Choose a method", "じゃあ、航空便でお願いします。",
      ["急ぐので、航空便にします。", "安いほうがいいので、船便でお願いします。", "航空便で。"]),
     ("かしこまりました。中身は何ですか。", "Certainly. What's inside?", "Say what's inside", "本と服です。",
      ["本と洋服が入っています。", "お菓子と本です。", "中身は本です。"]),
     ("電池やスプレーは入っていませんか。", "There are no batteries or sprays in it?", "Confirm", "はい、入っていません。",
      ["入っていません。", "大丈夫です。本と服だけです。", "そういうものはありません。"]),
     ("では、こちらの用紙に記入してください。追跡番号はここに書いてあります。", "Then please fill in this form. The tracking number is written here.", "Fill it in and ask about tracking", "書きました。この番号で、インターネットで調べられますか。",
      ["はい、わかりました。", "この番号で荷物の場所がわかりますか。", "書きました。これでいいですか。"]),
     ("はい、大丈夫です。料金は二千四百円です。ありがとうございました。", "Yes, that's all fine. That's 2,400 yen. Thank you.", "Pay and thank", "はい、お願いします。ありがとうございました。",
      ["カードでお願いします。", "現金でお願いします。", "はい、これでお願いします。"])])

scenario("restaurant-allergy", "Food allergy at a restaurant", "レストランでアレルギーを伝える", 3, "1+", "health",
    "A family restaurant. You are allergic to shrimp and want to order safely.",
    "Customer", "Server", "polite",
    ["State the allergy clearly", "Ask about ingredients", "Order a safe dish"],
    ["アレルギー", "海老", "入る", "料理", "材料", "大丈夫"],
    ["海老のアレルギーがあります", "これに海老は入っていますか", "海老が入っていない料理はありますか"],
    [("ご注文はお決まりですか。", "Are you ready to order?", "State the allergy", "すみません、海老のアレルギーがあるんですが。",
      ["海老が食べられないんです。", "注文の前に、ちょっといいですか。海老のアレルギーがあります。", "海老のアレルギーがあります。"]),
     ("かしこまりました。どちらのお料理が気になりますか。", "Understood. Which dish are you wondering about?", "Ask about a dish", "このパスタに海老は入っていますか。",
      ["このパスタは大丈夫ですか。", "このパスタの材料を教えてください。", "パスタに海老は入っていますか。"]),
     ("申し訳ございません、そちらのパスタには海老が入っております。", "I'm sorry, that pasta does contain shrimp.", "Ask for alternatives", "海老が入っていない料理はありますか。",
      ["ほかに大丈夫な料理はありますか。", "海老なしで作ってもらえますか。", "じゃあ、何がおすすめですか。"]),
     ("こちらのハンバーグでしたら、海老は使っておりません。", "This hamburg steak doesn't use any shrimp.", "Check about cross-contamination", "同じ鍋や油で作りますか。",
      ["揚げ物と同じ油を使いますか。", "調理器具は別ですか。", "少しでも入ると困るんですが、大丈夫ですか。"]),
     ("キッチンに確認しましたが、別に調理いたしますので大丈夫です。", "I checked with the kitchen: it's prepared separately, so it's fine.", "Order it", "じゃあ、ハンバーグをお願いします。",
      ["安心しました。ハンバーグにします。", "ハンバーグのセットをください。", "では、それをお願いします。"]),
     ("かしこまりました。アレルギーのことは、キッチンにも伝えておきます。", "Certainly. I'll let the kitchen know about your allergy too.", "Thank", "ありがとうございます。助かります。",
      ["よろしくお願いします。", "ありがとうございます。", "ご親切にありがとうございます。"])])

scenario("landlord", "Apartment problem", "アパートのトラブル", 3, "1+", "daily",
    "You call your building's management company because the water heater is broken.",
    "Tenant", "Management company staff", "polite",
    ["Explain the problem", "Say since when", "Arrange a repair visit"],
    ["お湯", "出る", "故障", "修理", "部屋", "都合"],
    ["お湯が出ないんです", "修理に来てもらえますか", "明日の午前中なら大丈夫です"],
    [("はい、みどり不動産です。", "Hello, Midori Real Estate.", "Say who you are", "さくらハイツ二〇三号室のパクです。",
      ["もしもし、さくらハイツに住んでいるパクと申します。", "二〇三号室のパクです。", "さくらハイツのパクです。お湯のことで電話しました。"]),
     ("いつもお世話になっております。どうされましたか。", "Thank you for calling. What's the problem?", "Explain the problem", "お湯が出なくなってしまったんです。",
      ["お湯が出ないんです。", "給湯器が故障したみたいです。", "シャワーから水しか出ないんです。"]),
     ("それはお困りですね。いつからですか。", "That must be a nuisance. Since when?", "Say since when", "今朝からです。",
      ["昨日の夜からです。", "今朝、シャワーを使おうとしたら出ませんでした。", "今日の朝からです。"]),
     ("給湯器の画面に、何か数字が出ていませんか。", "Is any number showing on the water heater's display?", "Report what you see", "「一一一」と出ています。",
      ["エラーの番号が出ています。", "何も出ていません。", "ちょっと見てきます。…一一一です。"]),
     ("そうですか。一度電源を切って、もう一度入れてみていただけますか。", "I see. Could you try turning the power off and on again?", "Say you tried and it didn't help", "もうやってみましたが、だめでした。",
      ["今やってみます。…やっぱり出ません。", "試しましたが、お湯は出ません。", "やってみましたが、同じです。"]),
     ("わかりました。故障かもしれませんね。修理業者に連絡します。ご都合のいい日はありますか。", "Understood. It may be broken. I'll contact a repair company. When is convenient for you?", "Arrange a time", "明日の午前中なら大丈夫です。",
      ["明日の午前中は家にいます。", "明日ならいつでも大丈夫です。", "できれば、今日来てもらえますか。"]),
     ("業者に確認して、明日の十時ごろ伺うように手配します。", "I'll check with the company and arrange for them to come around ten tomorrow.", "Confirm", "十時ですね。わかりました。",
      ["明日の十時ですね。", "はい、お願いします。", "十時なら大丈夫です。"]),
     ("修理代については、こちらで確認してからご連絡しますね。", "As for the repair cost, I'll look into it and get back to you.", "Ask whether you have to pay", "修理代は払わなければいけませんか。",
      ["修理代は大家さんが払うんですか。", "お金がかかるんですか。", "はい、よろしくお願いします。"]),
     ("普通に使っていて壊れたのなら、大家さんの負担になりますので、ご安心ください。ご不便をおかけしますが、よろしくお願いします。", "If it broke through normal use, the owner pays, so don't worry. Sorry for the inconvenience.", "Thank and close", "わかりました。よろしくお願いします。",
      ["安心しました。ありがとうございます。", "よろしくお願いします。失礼します。", "ありがとうございました。"])])

scenario("emergency-119", "Calling 119", "119番に電話する", 3, "1+", "health",
    "Someone has collapsed on the street. You call 119. Stay calm and give facts.",
    "Caller", "Emergency dispatcher, calm and structured", "polite",
    ["Say it's an ambulance call", "Give the location", "Describe the person's condition", "Follow instructions"],
    ["救急車", "場所", "倒れる", "意識", "呼吸", "住所"],
    ["救急車をお願いします", "人が倒れています", "意識がありません"],
    [("はい、119番消防です。火事ですか、救急ですか。", "119, fire department. Fire or ambulance?", "Say ambulance", "救急です。救急車をお願いします。",
      ["救急です。", "救急車をお願いします。", "救急です。人が倒れています。"]),
     ("救急車が向かう住所を教えてください。", "Tell me the address the ambulance should go to.", "Give the location", "中央駅の東口の前です。",
      ["住所はわかりませんが、中央駅の東口です。", "中央駅東口のバス停の前です。", "中央駅の近くです。東口を出たところです。"]),
     ("中央駅の東口ですね。近くに目印になるものはありますか。", "Central Station, east exit. Is there a landmark nearby?", "Name a landmark", "大きい銀行の前です。",
      ["コンビニの前です。", "時計台の下です。", "バス停の近くです。"]),
     ("どうしましたか。", "What happened?", "Describe what happened", "男の人が急に倒れました。",
      ["男の人が倒れています。", "おじいさんが道で倒れて、動きません。", "人が倒れました。"]),
     ("その方は何歳ぐらいですか。", "About how old is the person?", "Estimate the age", "七十歳ぐらいだと思います。",
      ["七十代ぐらいです。", "おじいさんです。", "よくわかりませんが、お年寄りです。"]),
     ("肩をたたいて、大きな声で呼びかけてください。反応はありますか。", "Tap his shoulder and call to him loudly. Is there any response?", "Report consciousness", "反応がありません。意識がないみたいです。",
      ["呼んでも答えません。", "目は開いていますが、話せません。", "少し反応があります。"]),
     ("呼吸はしていますか。胸やおなかが動いているか見てください。", "Is he breathing? Look whether his chest or belly is moving.", "Report breathing", "はい、呼吸はしています。",
      ["胸が動いています。息はしています。", "よくわかりません。", "息をしていないみたいです。"]),
     ("わかりました。念のため、近くにAEDがあれば、周りの人に持ってきてもらってください。", "Understood. Just in case, if there's an AED nearby, ask people around to bring it.", "Say you'll ask someone", "わかりました。周りの人に頼みます。",
      ["駅の人に頼みます。", "駅にあると思います。取ってきてもらいます。", "今、頼みました。"]),
     ("あなたのお名前と、今かけている電話番号を教えてください。", "Please tell me your name and the number you're calling from.", "Give name and phone number", "リンです。電話番号は〇九〇・一二三四・五六七八です。",
      ["リンと申します。番号は〇九〇・一二三四・五六七八です。", "名前はリンです。この携帯の番号です。", "リンです。"]),
     ("救急車はもう向かっています。着くまで、その方のそばにいてください。様子が変わったら、また電話してください。", "The ambulance is already on its way. Stay with him until it arrives. If his condition changes, call again.", "Confirm", "わかりました。ここで待ちます。",
      ["はい、そばにいます。", "わかりました。何かあったら、また電話します。", "はい。"]),
     ("サイレンが聞こえたら、手を振って場所を知らせてください。電話を切って大丈夫です。", "When you hear the siren, wave so they can see where you are. You can hang up now.", "Acknowledge and hang up", "わかりました。ありがとうございます。",
      ["はい、手を振ります。", "わかりました。よろしくお願いします。", "はい、失礼します。"])])

scenario("returns", "Returning an item", "返品する", 3, "1+", "daily",
    "A clothing shop. The shirt you bought yesterday is the wrong size.",
    "Customer", "Shop staff", "polite",
    ["Explain the problem", "Show the receipt", "Choose exchange or refund"],
    ["返品", "交換", "サイズ", "レシート", "返金"],
    ["昨日買ったんですが", "サイズが合わなくて", "交換できますか"],
    [("いらっしゃいませ。", "Welcome.", "Explain the problem", "昨日このシャツを買ったんですが、サイズが合わなくて。",
      ["すみません、このシャツ、サイズが合わなかったんですが。", "昨日買ったシャツのサイズが小さかったんです。", "このシャツ、サイズを交換できますか。"]),
     ("さようでございますか。レシートはお持ちですか。", "I see. Do you have the receipt?", "Show it", "はい、こちらです。",
      ["はい、あります。", "はい、これです。", "はい、どうぞ。"]),
     ("ありがとうございます。交換と返金、どちらになさいますか。", "Thank you. Would you like an exchange or a refund?", "Ask to exchange it for a bigger size", "Lサイズに交換できますか。",
      ["大きいサイズに交換してください。", "交換でお願いします。一つ上のサイズはありますか。", "Lサイズがあれば、交換したいです。"]),
     ("少々お待ちください。…Lサイズ、ございました。ご試着なさいますか。", "One moment, please. ... We have a large. Would you like to try it on?", "Say you want to try it", "はい、試着してもいいですか。",
      ["はい、お願いします。", "試着室はどこですか。", "はい、着てみます。"]),
     ("どうぞ、あちらの試着室をお使いください。…いかがでしたか。", "Please use the fitting room over there. ... How was it?", "Report and decide", "ちょうどいいです。これにします。",
      ["ぴったりです。これでお願いします。", "大丈夫でした。交換してください。", "これでいいです。"]),
     ("では、こちらと交換いたします。またのご来店をお待ちしております。", "Then I'll exchange it for this one. We look forward to seeing you again.", "Thank", "ありがとうございました。",
      ["どうもありがとうございます。", "助かりました。ありがとうございます。", "お世話になりました。"])])

scenario("hair-salon", "At the hair salon", "美容院で", 3, "1+", "daily",
    "A hair salon. You want a trim, not a big change.",
    "Customer", "Stylist, chatty", "polite",
    ["Explain how you want your hair", "Answer small talk", "Give feedback at the end"],
    ["髪", "切る", "短い", "前髪", "少し", "長さ"],
    ["少しだけ切ってください", "前髪は眉ぐらいで", "ちょうどいいです"],
    [("いらっしゃいませ。今日はどうしますか。", "Welcome. What would you like today?", "Explain what you want", "少しだけ切ってください。",
      ["毛先をそろえるくらいでお願いします。", "あまり変えないで、少し切ってください。", "カットをお願いします。短くしすぎないでください。"]),
     ("どのくらい切りますか。", "How much should I cut?", "Say how much", "三センチぐらいお願いします。",
      ["二、三センチでお願いします。", "肩にかかるくらいの長さにしてください。", "三センチぐらいで。"]),
     ("前髪はどうしますか。", "What about the fringe?", "Explain", "眉ぐらいの長さにしてください。",
      ["前髪は少しだけ切ってください。", "前髪はそのままで大丈夫です。", "眉にかかるくらいでお願いします。"]),
     ("今日はお仕事帰りですか。", "On your way home from work today?", "Make small talk", "はい、今日は早く終わりました。",
      ["いいえ、今日は休みです。", "はい、仕事の帰りです。", "そうです。今日は早く終わったので。"]),
     ("はい、こんな感じでいかがですか。後ろはこうなっています。", "There. How does this look? This is the back.", "Give feedback", "いいですね。ちょうどいいです。",
      ["すごくいいです。", "ちょうどいい長さです。ありがとうございます。", "いい感じです。"]),
     ("ありがとうございます。お疲れさまでした。", "Thank you. All done.", "Thank", "ありがとうございました。",
      ["どうもありがとうございました。", "ありがとうございます。また来ます。", "お世話になりました。"])])

scenario("gym", "Signing up at a gym", "ジムに入会する", 3, "1+", "daily",
    "The front desk of a local gym.",
    "Prospective member", "Gym staff", "polite",
    ["Say you want to join", "Ask about fees and hours", "Ask about a trial"],
    ["入会", "料金", "月", "体験", "営業時間", "会員"],
    ["入会したいんですが", "月いくらですか", "体験はできますか"],
    [("こんにちは。見学ですか。", "Hello. Here to look around?", "Say you want to join", "入会したいと思っているんですが。",
      ["入会したいんですが。", "会員になりたいです。", "はい。入会を考えています。"]),
     ("ありがとうございます。何かご質問はありますか。", "Thank you. Any questions?", "Ask about fees", "料金は月いくらですか。",
      ["月の料金を教えてください。", "一か月いくらですか。", "会費はいくらですか。"]),
     ("月八千円です。夜だけの会員なら、月五千円です。", "8,000 yen a month. A night-only membership is 5,000 yen a month.", "Ask about hours", "営業時間は何時から何時までですか。",
      ["何時まで開いていますか。", "朝は何時から使えますか。", "夜だけの会員は何時から使えますか。"]),
     ("朝七時から夜十一時までです。夜の会員は、夕方六時からご利用いただけます。", "7 a.m. to 11 p.m. Night members can use it from 6 p.m.", "Ask about a trial", "体験はできますか。",
      ["入会する前に、一度体験できますか。", "無料の体験はありますか。", "試しに使ってみることはできますか。"]),
     ("はい、一回無料で体験できます。", "Yes, you can try it once for free.", "Book it", "じゃあ、明日体験したいです。",
      ["明日の夜、体験できますか。", "今週の土曜日にお願いします。", "じゃあ、体験をお願いします。"]),
     ("では、こちらにお名前と、来られる日時を書いてください。", "Then please write your name and when you'll come here.", "Fill it in", "書きました。これでいいですか。",
      ["はい、書きます。", "グエンです。明日の七時に来ます。", "できました。"]),
     ("はい、大丈夫です。運動しやすい服とタオルを持ってきてください。お待ちしております。", "Yes, that's fine. Bring comfortable clothes and a towel. We look forward to seeing you.", "Thank and close", "わかりました。よろしくお願いします。",
      ["ありがとうございます。楽しみです。", "タオルですね。わかりました。", "ありがとうございました。"])])

scenario("making-friends", "Making a friend", "友達を作る", 5, "0+", "social",
    "A language exchange café. Someone your age starts chatting.",
    "Learner", "Friendly student", "casual",
    ["Exchange names", "Talk about hobbies", "Suggest meeting again"],
    ["趣味", "名前", "好き", "映画", "今度", "一緒"],
    ["趣味は何？", "私も好き！", "今度一緒に行こう"],
    [("こんにちは！はじめまして。名前、聞いてもいい？", "Hi! Nice to meet you. Can I ask your name?", "Say your name", "サラだよ。よろしくね！",
      ["サラです。よろしく！", "はじめまして、サラです。", "サラ。よろしくね。"]),
     ("私はゆい。どこから来たの？", "I'm Yui. Where are you from?", "Say where you're from", "カナダから来たよ。",
      ["カナダ。", "カナダのトロントから来た。", "カナダ出身だよ。"]),
     ("へえ、いいね。趣味は何？", "Oh, nice. What are your hobbies?", "Talk about hobbies and ask back", "映画を見るのが好き。ゆいは？",
      ["映画かな。ゆいの趣味は？", "よく映画を見るよ。", "映画が好き！ゆいは何が好き？"]),
     ("私は映画、大好き！最近はアニメ映画ばっかり見てる。", "I love movies! Lately I watch nothing but anime films.", "Suggest meeting again", "今度一緒に見に行かない？",
      ["じゃあ、今度一緒に行こうよ。", "今度映画見に行こう！", "よかったら、一緒に行かない？"]),
     ("いいね、行こう！じゃあ、LINE交換しよう。", "Sounds good, let's go! Let's swap LINE then.", "Agree and close", "うん、しよう！楽しみにしてるね。",
      ["いいよ。これ、私のQRコード。", "うん！また連絡するね。", "オッケー。楽しみ！"])])

scenario("late-apology", "Apologising for being late", "遅刻を謝る", 4, "1", "work",
    "You arrive fifteen minutes late to a team meeting because the train was delayed.",
    "Team member", "Team leader, a little annoyed", "polite",
    ["Apologise sincerely", "Explain the reason briefly", "Say how you'll avoid it"],
    ["遅刻", "遅れる", "電車", "申し訳ない", "気をつける"],
    ["遅れて申し訳ありません", "電車が遅れてしまって", "今後気をつけます"],
    [("あ、来ましたか。", "Oh, you're here.", "Apologise", "遅れて申し訳ありません。",
      ["遅くなってすみません。", "遅刻してしまい、申し訳ありません。", "本当にすみません。"]),
     ("どうしたんですか。", "What happened?", "Explain briefly", "電車が事故で止まってしまって。",
      ["電車が遅れてしまいました。", "事故で電車が止まっていました。", "電車の遅れです。すみません。"]),
     ("そうですか。でも、遅れるなら連絡がほしかったですね。", "I see. But if you're going to be late, I'd have liked a message.", "Accept it and say how you'll avoid it", "連絡しなくてすみませんでした。今後気をつけます。",
      ["おっしゃる通りです。次からはすぐに連絡します。", "すみません。これからは必ず連絡します。", "申し訳ありません。気をつけます。"]),
     ("わかりました。今、来月の予算の話をしていたところです。", "All right. We were just discussing next month's budget.", "Ask to catch up", "あとで資料を見せていただけますか。",
      ["会議のあとで、内容を教えていただけますか。", "資料はどこにありますか。", "すみません、今どこまで進みましたか。"]),
     ("資料は共有フォルダにあります。じゃあ、続けましょう。", "The materials are in the shared folder. Let's carry on.", "Thank and join in", "ありがとうございます。",
      ["はい、わかりました。", "承知しました。ありがとうございます。", "はい、よろしくお願いします。"])])

scenario("decline-invitation", "Declining an invitation", "誘いを断る", 3, "1+", "social",
    "A coworker invites you to drinks tonight, but you already have plans.",
    "Coworker", "Friendly coworker", "polite",
    ["Thank them for the invitation", "Decline softly", "Suggest another time"],
    ["誘う", "今夜", "予定", "残念", "今度"],
    ["誘ってくれてありがとう", "今日はちょっと…", "また誘ってください"],
    [("今夜、みんなで飲みに行くんだけど、来ない？", "We're all going for drinks tonight. Want to come?", "Thank them and decline softly", "誘ってくれてありがとうございます。でも、今日はちょっと…。",
      ["ありがとうございます。今日はちょっと予定があって…。", "すみません、今夜は先約があるんです。", "行きたいんですけど、今日はちょっと難しいです。"]),
     ("そうか、残念。何かあるの？", "Oh, too bad. Got something on?", "Give a brief reason", "友達と約束があるんです。",
      ["家族と食事の予定があって。", "ちょっと用事があるんです。", "前から予定が入っていて。"]),
     ("そっか、それじゃしょうがないね。", "I see, can't be helped then.", "Suggest another time", "また今度誘ってください。",
      ["来週なら行けます。", "次は絶対行きます！", "また誘ってくださいね。"]),
     ("じゃあ、来週の金曜日はどう？", "How about next Friday then?", "Accept", "金曜日ならぜひ。",
      ["はい、大丈夫です。", "来週の金曜日、空いています。行きます！", "ぜひ行きたいです。"]),
     ("よし、決まりだね。じゃあ、お疲れさま。", "Great, it's settled. See you, good work today.", "Close warmly", "楽しみにしています。お疲れさまでした。",
      ["お疲れさまでした。", "ありがとうございます。お先に失礼します。", "楽しみです。お疲れさまでした。"])])

scenario("favor-senior", "Asking a senior colleague for a favor", "先輩にお願いする", 3, "2", "work",
    "You need a senior colleague to check your report before a deadline.",
    "Junior colleague", "Busy senior colleague (先輩)", "polite",
    ["Check if they have time", "Make the request with appropriate softness", "Thank them properly"],
    ["お願い", "報告書", "確認", "締め切り", "時間", "助かる"],
    ["今ちょっとよろしいですか", "…していただけませんか", "助かります"],
    [("ん？どうした？", "Hm? What's up?", "Check they have time", "先輩、今ちょっとよろしいですか。",
      ["お忙しいところすみません、少しお時間いただけますか。", "先輩、ちょっといいですか。", "すみません、今お時間ありますか。"]),
     ("いいよ、何？", "Sure, what is it?", "Make the request", "この報告書を確認していただけませんか。",
      ["報告書を見ていただけないでしょうか。", "この報告書、チェックしていただけますか。", "もしよろしければ、報告書を確認していただきたいんですが。"]),
     ("締め切りはいつ？", "When's the deadline?", "Give the deadline", "明日の朝です。",
      ["明日の九時までです。", "明日の朝一番に出します。", "明日の午前中です。"]),
     ("今日はちょっと忙しいから、夕方でもいい？", "I'm a bit busy today. Is this evening okay?", "Accept gratefully", "はい、夕方で大丈夫です。助かります。",
      ["もちろんです。ありがとうございます。", "はい、いつでも大丈夫です。", "夕方で十分です。助かります。"]),
     ("どこを特に見ればいい？", "What should I look at especially?", "Explain", "特に数字が合っているか見ていただきたいです。",
      ["表の数字を中心に見ていただけますか。", "結論のところがわかりやすいか見ていただきたいです。", "敬語の使い方を見ていただけると助かります。"]),
     ("わかった。じゃあ、あとで机に置いといて。", "Got it. Leave it on my desk later, then.", "Thank properly", "お忙しいところ、ありがとうございます。",
      ["ありがとうございます。よろしくお願いします。", "本当に助かります。机に置いておきます。", "すみません、よろしくお願いいたします。"])])

scenario("customer-service-keigo", "Keigo customer service", "敬語で接客する", 2, "2", "work",
    "You work at a hotel front desk and a guest has a complaint about noise.",
    "Hotel staff", "Guest, irritated but reasonable", "keigo",
    ["Apologise in keigo", "Ask for details", "Offer a solution"],
    ["騒音", "部屋", "変更", "申し訳ございません", "確認"],
    ["大変申し訳ございません", "詳しくお聞かせいただけますか", "お部屋を変更いたしましょうか"],
    [("すみません、隣の部屋がうるさくて眠れないんです。", "Excuse me, the next room is so noisy I can't sleep.", "Apologise", "大変申し訳ございません。",
      ["ご迷惑をおかけして、申し訳ございません。", "大変失礼いたしました。", "誠に申し訳ございません。"]),
     ("昨日からずっとなんですよ。", "It's been like this since yesterday.", "Ask for the room number", "お部屋番号をお伺いしてもよろしいでしょうか。",
      ["恐れ入りますが、お部屋番号を教えていただけますか。", "お部屋番号をお教えいただけますでしょうか。", "お部屋は何号室でいらっしゃいますか。"]),
     ("六一二号室です。", "Room 612.", "Ask for details", "どのような音か、詳しくお聞かせいただけますか。",
      ["何時ごろからうるさいか、お聞かせいただけますでしょうか。", "話し声でしょうか、それとも音楽でしょうか。", "詳しい状況をお伺いしてもよろしいでしょうか。"]),
     ("夜中まで大きな声で話していて、壁をたたく音もするんです。", "They talk loudly until the middle of the night, and there's banging on the wall too.", "Say what you'll do and offer a room change", "隣のお客様に注意いたします。また、よろしければお部屋を変更いたしましょうか。",
      ["すぐに隣のお部屋にご連絡いたします。お部屋の変更もできますが、いかがなさいますか。", "よろしければ、お部屋を変更いたしましょうか。", "係の者から注意させていただきます。"]),
     ("部屋を変えてもらえるなら助かります。", "If you can change my room, that would help.", "Explain the next step", "ただいま空いているお部屋を確認いたします。",
      ["かしこまりました。空いているお部屋をお調べいたします。", "少々お待ちくださいませ。確認いたします。", "承知いたしました。すぐにお調べいたします。"]),
     ("どのくらいかかりますか。", "How long will it take?", "Give a time", "五分ほどお待ちいただけますでしょうか。",
      ["五分ほどで確認いたします。", "少々お時間をいただけますでしょうか。", "すぐにお調べいたしますので、こちらでお待ちください。"]),
     ("同じタイプの部屋はありますか。", "Is there a room of the same type?", "Explain there isn't, and offer an upgrade at no extra charge", "あいにく同じタイプはございませんが、上の階のツインルームを追加料金なしでご用意いたします。",
      ["同じお部屋はございませんが、ツインルームでしたらすぐにご用意できます。料金はそのままでございます。", "あいにく満室でございますが、上の階のお部屋をご用意いたします。", "ツインルームでよろしければ、同じ料金でご案内いたします。"]),
     ("それなら、お願いします。", "In that case, yes please.", "Close politely", "かしこまりました。ご迷惑をおかけして、誠に申し訳ございませんでした。",
      ["承知いたしました。すぐに新しい鍵をお持ちいたします。", "かしこまりました。ご不便をおかけして申し訳ございません。", "ありがとうございます。お荷物は係の者がお運びいたします。"])])

scenario("casual-chat", "Casual chat with a friend", "友達と雑談", 4, "1", "social",
    "You meet a friend for coffee on the weekend.",
    "Friend", "Close friend", "casual",
    ["Talk about your week", "Ask about theirs", "Make weekend plans"],
    ["週末", "最近", "忙しい", "疲れる", "遊ぶ"],
    ["最近どう？", "めっちゃ忙しかった", "週末何する？"],
    [("久しぶり！最近どう？", "Long time no see! How've you been?", "Talk about your week", "仕事がめっちゃ忙しかったよ。",
      ["まあまあかな。仕事が大変だった。", "忙しかった！残業ばっかり。", "元気だよ。でも、ちょっと忙しかった。"]),
     ("大変だったね。疲れてない？", "That sounds rough. Aren't you tired?", "Answer and ask about theirs", "ちょっと疲れてる。そっちはどう？",
      ["疲れた〜。そっちは？", "大丈夫。そっちは最近どう？", "うん、ちょっとね。そっちは忙しかった？"]),
     ("私は今週テストがあって、ずっと勉強してた。", "I had exams this week, so I was studying the whole time.", "React and ask how it went", "テストどうだった？",
      ["えー、大変！どうだった？", "お疲れさま。テストできた？", "そうなんだ。うまくいった？"]),
     ("まあまあかな。やっと終わってほっとしてる。", "Not bad, I guess. I'm relieved it's finally over.", "Ask about weekend plans", "週末、何か予定ある？",
      ["じゃあ、週末はひま？", "週末は何するの？", "今度の週末、空いてる？"]),
     ("特にないよ。何かしたいことある？", "Nothing in particular. Anything you want to do?", "Propose something and a time", "映画でも見に行かない？土曜日の午後はどう？",
      ["土曜日、買い物に行こうよ。", "じゃあ、日曜日にカラオケ行かない？", "映画見に行こう！"]),
     ("いいね、行こう！じゃあ、詳しいことはまたLINEするね。", "Sounds good, let's go! I'll LINE you the details.", "Agree and close", "オッケー。楽しみにしてる！",
      ["うん、待ってる。", "やった！じゃあ、またね。", "わかった。連絡待ってるね。"])])

scenario("party-smalltalk", "Small talk at a party", "パーティーで雑談", 3, "1+", "social",
    "A welcome party for new staff. You meet someone from another department.",
    "Guest", "Colleague from another department", "polite",
    ["Introduce yourself", "Find a common topic", "End the chat gracefully"],
    ["部署", "出身", "趣味", "料理", "おいしい"],
    ["どちらの部署ですか", "私も…が好きです", "またお話ししましょう"],
    [("はじめまして。経理部の山田です。", "Nice to meet you. I'm Yamada from accounting.", "Introduce yourself", "はじめまして。営業部のジョーンズです。",
      ["営業部のジョーンズと申します。よろしくお願いします。", "はじめまして、ジョーンズです。営業部にいます。", "ジョーンズです。よろしくお願いします。"]),
     ("日本は長いんですか。", "Have you been in Japan long?", "Answer", "まだ半年です。",
      ["来て半年ぐらいです。", "いいえ、まだ半年なんです。", "三年ぐらいになります。"]),
     ("そうなんですか。日本語、お上手ですね。", "Really? Your Japanese is very good.", "Respond modestly", "いえいえ、まだまだです。",
      ["ありがとうございます。まだ勉強中です。", "いえ、そんなことないです。", "ありがとうございます。がんばっています。"]),
     ("週末は何をしているんですか。", "What do you do on weekends?", "Share a hobby", "よく山に登っています。",
      ["山登りが好きなので、よく山に行きます。", "ハイキングによく行きます。", "週末は山に登ることが多いです。"]),
     ("本当ですか。私も登山が好きなんです！", "Really? I love hiking too!", "Follow up", "おすすめの山はありますか。",
      ["どこの山によく行きますか。", "本当ですか！どの山がおすすめですか。", "そうなんですか。最近どこに登りましたか。"]),
     ("近くなら、高尾山がいいですよ。電車で行けますし。", "Nearby, Mt. Takao is good. You can get there by train, too.", "React", "今度行ってみます。",
      ["いいですね。ぜひ行ってみたいです。", "電車で行けるのはいいですね。", "今度一緒に登りませんか。"]),
     ("あ、すみません、部長に呼ばれてしまいました。", "Oh, sorry, the manager is calling me.", "End gracefully", "またお話ししましょう。",
      ["どうぞどうぞ。またゆっくりお話ししましょう。", "いえいえ、またお話を聞かせてください。", "はい、またぜひ。"])])

scenario("short-report", "Presenting a short report", "短い報告をする", 2, "2+", "work",
    "A weekly team meeting. You report on project progress.",
    "Team member presenting", "Manager asking follow-up questions", "polite",
    ["Give the status", "Explain one problem and its cause", "Propose a next step", "Answer a follow-up question"],
    ["報告", "進捗", "問題", "原因", "対策", "予定"],
    ["進捗をご報告します", "問題が一つあります", "来週までに…する予定です"],
    [("では、進捗を報告してください。", "Please report on progress.", "Give the status", "進捗をご報告します。全体の七割が終わりました。",
      ["現在、七割ほど完了しております。", "全体の七十パーセントが終わっています。", "進捗についてご報告します。予定の七割まで進んでいます。"]),
     ("順調ですか。", "Is it on track?", "Explain a problem", "一つ問題があります。テストが遅れています。",
      ["おおむね順調ですが、テストが少し遅れています。", "問題が一つあります。テストの作業が予定より遅れています。", "実は、テストが遅れています。"]),
     ("原因は何ですか。", "What's the cause?", "Explain the cause", "担当者が一人休んでいるためです。",
      ["担当者が一人、病気で休んでいるためです。", "人が足りていないのが原因です。", "テストの担当者が一人休みを取っているためです。"]),
     ("どのくらい遅れていますか。", "How far behind is it?", "Quantify the delay", "三日ほど遅れています。",
      ["今のところ三日の遅れです。", "予定より三日遅れています。", "二、三日です。"]),
     ("対策はありますか。", "Do you have a countermeasure?", "Propose a next step", "来週から他のチームに手伝ってもらう予定です。",
      ["他のチームから一人応援をお願いしようと考えています。", "テストの順番を変えて、重要なところから進めます。", "残業で対応する予定です。"]),
     ("関係者の了解は取れていますか。", "Have you got agreement from the people involved?", "Answer honestly", "まだです。今日中に確認します。",
      ["はい、昨日了解をもらいました。", "これから相談するところです。", "まだですが、今日の午後に話す予定です。"]),
     ("締め切りには間に合いそうですか。", "Will you make the deadline?", "Give your outlook", "はい、間に合う見込みです。",
      ["今の対策がうまくいけば、間に合います。", "ぎりぎりですが、間に合うと思います。", "間に合わない可能性もあるので、来週またご報告します。"]),
     ("お客様への報告はどうしますか。", "What about reporting to the client?", "Propose how to handle it", "今週の金曜日に、進捗と一緒にご説明する予定です。",
      ["遅れが確定したら、すぐにお客様に連絡します。", "金曜日の定例会議で報告します。", "ご相談してから決めたいと思います。"]),
     ("わかりました。何か困ったことがあれば、早めに相談してください。", "Understood. If anything comes up, talk to me early.", "Acknowledge", "はい、ありがとうございます。",
      ["承知しました。", "はい、そうします。", "わかりました。早めにご相談します。"]),
     ("では、ほかになければ、次の議題に移りましょう。ありがとう。", "Then, if there's nothing else, let's move to the next item. Thank you.", "Close the report", "以上です。ありがとうございました。",
      ["以上です。", "特にありません。以上です。", "今日の報告は以上になります。"])])

scenario("taxi", "Taking a taxi", "タクシーに乗る", 5, "0+", "travel",
    "You get into a taxi outside a hotel.",
    "Passenger", "Taxi driver", "polite",
    ["Say the destination", "Ask about time", "Pay"],
    ["空港", "時間", "かかる", "領収書", "止める"],
    ["…までお願いします", "どのくらいかかりますか", "ここで止めてください"],
    [("どちらまでですか。", "Where to?", "Say the destination", "空港までお願いします。",
      ["空港に行ってください。", "空港の第一ターミナルまでお願いします。", "空港まで。"]),
     ("空港ですね。ターミナルはどちらですか。", "The airport. Which terminal?", "Say the terminal", "第一ターミナルです。",
      ["第一でお願いします。", "たぶん第一だと思います。", "第一ターミナルでお願いします。"]),
     ("高速を使うと四十分ぐらいですが、よろしいですか。", "Using the expressway it's about forty minutes. Is that okay?", "Agree", "はい、お願いします。",
      ["はい、高速でお願いします。", "大丈夫です。急いでいるので。", "はい、それでいいです。"]),
     ("お待たせしました、着きました。六千二百円です。", "Thanks for waiting, we've arrived. That's 6,200 yen.", "Pay and ask for a receipt", "カードでお願いします。領収書もください。",
      ["領収書をお願いします。", "現金で払います。領収書をいただけますか。", "これでお願いします。"]),
     ("はい、領収書です。ありがとうございました。お気をつけて。", "Here's your receipt. Thank you. Have a safe trip.", "Thank and get out", "ありがとうございました。",
      ["どうも、ありがとうございます。", "お世話になりました。", "ありがとうございます。"])])


HERE = Path(__file__).resolve().parent
OUT = HERE / "scenarios.json"
BATCHES = HERE / "batches"
NOTE = "Scenarios and scripted turns drafted by an LLM; review with tools/items/review.py before marking verified."
ILR_FOR_JLPT = {5: "0+", 4: "1", 3: "1+", 2: "2", 1: "2+"}

DRAFT_SYSTEM = (
    "You write original Japanese role-play scenarios for learners: a situation, roles, goals, key vocabulary and a "
    "scripted fallback conversation. Never copy textbooks or apps; invent names and places; keep it PG. Japanese "
    "must be natural, grammatical, at the requested JLPT level and consistently in the requested register. "
    "Reply with a single JSON object only."
)


def draft_messages(args, avoid: list[str]) -> list[dict]:
    user = (
        f"Write one role-play scenario at JLPT N{args.level}, category {args.category}"
        + (f", situation: {args.topic}" if args.topic else "")
        + f". Use {args.turns} scripted partner turns (between 4 and 12). Each turn: the partner's line (Japanese "
        "+ English), what the learner should do next (English), one good learner reply in Japanese (sample) and 2-3 "
        "other acceptable replies (accept). The last partner line closes the conversation appropriately. "
        "Vocabulary: 4-8 Japanese words in dictionary form.\n"
        + (f"Avoid these situations: {'; '.join(avoid[-30:])}.\n" if avoid else "")
        + 'JSON: {"titleEn","titleJa","setting" (English),"learnerRole","partnerRole","register":"casual|polite|keigo",'
        '"goals":[2-4 English],"vocabulary":[...],"phrases":[2-5 Japanese],"partnerNotes": English,'
        '"turns":[{"partnerJa","partnerEn","intent","sample","accept":[...]}]}'
    )
    return [{"role": "system", "content": DRAFT_SYSTEM}, {"role": "user", "content": user}]


def normalize(args, raw: dict) -> dict:
    nfc = pa.build_practice.nfc
    out = {
        "id": "", "titleEn": str(raw["titleEn"]).strip(), "titleJa": nfc(str(raw["titleJa"]).strip()),
        "jlpt": args.level, "ilr": ILR_FOR_JLPT[args.level], "category": args.category,
        "setting": str(raw["setting"]).strip(), "learnerRole": str(raw["learnerRole"]).strip(),
        "partnerRole": str(raw["partnerRole"]).strip(), "register": str(raw["register"]).strip().lower(),
        "goals": [str(g) for g in raw["goals"]], "vocabulary": [nfc(str(w)) for w in raw["vocabulary"]],
        "phrases": [nfc(str(p)) for p in raw["phrases"]],
        "turns": [
            {
                "partnerJa": nfc(t["partnerJa"]), "partnerEn": t["partnerEn"], "intent": t["intent"],
                "sample": nfc(t["sample"]),
                "accept": [nfc(a) for a in t.get("accept", []) if nfc(a) != nfc(t["sample"])],
            }
            for t in raw["turns"]
        ],
    }
    if raw.get("partnerNotes"):
        out["partnerNotes"] = str(raw["partnerNotes"]).strip()
    return out


def merge(force: bool = False) -> None:
    authored = [("author_scenarios.py", s) for s in S] + pa.load_batches(BATCHES, "scenarios")
    counts = pa.merge(OUT, "scenarios", authored, NOTE, force)
    print(f"scenarios.json: {counts['total']} scenarios ({counts['authored']} authored, "
          f"{counts['keptReviewed']} reviewed copies kept, {counts['keptJsonOnly']} only in the JSON)")


def main() -> None:
    sys.stdout.reconfigure(encoding="utf-8")
    ap = argparse.ArgumentParser(description="Merge or draft role-play scenarios.")
    ap.add_argument("--force", action="store_true", help="replace reviewed copies with the authored version")
    sub = ap.add_subparsers(dest="cmd")
    d = sub.add_parser("draft", help="draft new scenarios through an OpenAI-compatible endpoint")
    pa.add_draft_args(d)
    d.add_argument("--level", type=int, required=True, choices=(1, 2, 3, 4, 5), help="JLPT level (5 = N5)")
    d.add_argument("--category", required=True, choices=sorted(pa.build_practice.CATEGORIES))
    d.add_argument("--turns", type=int, default=8, help="scripted partner turns (4-12)")
    d.add_argument("--topic", help="optional situation to write about")
    args = ap.parse_args()
    if args.cmd == "draft":
        code = pa.draft(
            args=args, key="scenarios", out=OUT, batch_dir=BATCHES,
            messages=lambda avoid: draft_messages(args, avoid),
            normalize=lambda raw: normalize(args, raw),
            check=pa.build_practice.check_scenario,
            id_base=lambda e: f"{args.category}-{pa.slug(e['titleEn'])}",
        )
        merge(args.force)
        sys.exit(code)
    merge(args.force)


if __name__ == "__main__":
    main()
