"""Authoring source for tools/packs/listening/dialogues.json (LLM-drafted, source="llm", awaiting human review).

Regenerate: uv run python packs/listening/author_dialogues.py
Line: (speaker id, japanese, english, [gap words — must appear verbatim in the line and be JMdict words]).
Question: (english question, [choices], answer index).
"""

from __future__ import annotations

import json
from pathlib import Path

D = []

F = ("female", "adult")
M = ("male", "adult")
FY = ("female", "young")
MY = ("male", "young")
FO = ("female", "senior")
MO = ("male", "senior")


def dialogue(id_, title, jlpt, topic, a, b, lines, questions):
    D.append({
        "id": id_, "title": title, "jlpt": jlpt, "topic": topic,
        "speakers": [
            {"id": "A", "name": a[0], "voice": a[1][0], "age": a[1][1]},
            {"id": "B", "name": b[0], "voice": b[1][0], "age": b[1][1]},
        ],
        "lines": [{"speaker": s, "ja": ja, "en": en, "gaps": g} for s, ja, en, g in lines],
        "questions": [{"question": q, "choices": c, "answer": i} for q, c, i in questions],
    })


# ---------------------------------------------------------------- N5
dialogue("n5-morning", "Good morning", 5, "greetings", ("田中", F), ("リー", M), [
    ("A", "おはようございます。", "Good morning.", []),
    ("B", "おはようございます。今日は寒いですね。", "Good morning. It's cold today, isn't it?", ["今日", "寒い"]),
    ("A", "そうですね。リーさん、朝ごはんを食べましたか。", "It is. Lee, did you eat breakfast?", ["朝ごはん"]),
    ("B", "いいえ、まだです。", "No, not yet.", ["まだ"]),
    ("A", "じゃあ、一緒にパンを食べませんか。", "Then shall we eat some bread together?", ["一緒", "パン"]),
    ("B", "ありがとうございます。いただきます。", "Thank you. I'll have some.", []),
], [
    ("What is the weather like?", ["Hot", "Cold", "Rainy", "Windy"], 1),
    ("What does Tanaka offer?", ["Rice", "Coffee", "Bread", "Fruit"], 2),
])

dialogue("n5-shop-apples", "Buying apples", 5, "shopping", ("店員", MO), ("客", F), [
    ("A", "いらっしゃいませ。", "Welcome.", []),
    ("B", "すみません、このりんごはいくらですか。", "Excuse me, how much are these apples?", ["りんご"]),
    ("A", "一つ百円です。", "They're 100 yen each.", ["百"]),
    ("B", "じゃあ、三つください。", "Then three, please.", ["三つ"]),
    ("A", "はい、三百円です。", "Okay, that's 300 yen.", []),
    ("B", "はい、どうぞ。", "Here you are.", []),
    ("A", "ありがとうございました。", "Thank you very much.", []),
], [
    ("How much is one apple?", ["50 yen", "100 yen", "300 yen", "1,000 yen"], 1),
    ("How many apples does the customer buy?", ["One", "Two", "Three", "Four"], 2),
])

dialogue("n5-weekend", "Weekend plans", 5, "free time", ("ケン", MY), ("ゆき", FY), [
    ("A", "ゆきさん、週末は何をしますか。", "Yuki, what are you doing this weekend?", ["週末"]),
    ("B", "土曜日に友達と映画を見ます。", "On Saturday I'm seeing a movie with a friend.", ["土曜日", "映画"]),
    ("A", "いいですね。日曜日は？", "Nice. And Sunday?", ["日曜日"]),
    ("B", "日曜日は家で勉強します。ケンさんは？", "On Sunday I'll study at home. And you, Ken?", ["勉強"]),
    ("A", "私は山に行きます。", "I'm going to the mountains.", ["山"]),
    ("B", "へえ、楽しそうですね。", "Oh, that sounds fun.", []),
], [
    ("What will Yuki do on Saturday?", ["Study at home", "See a movie", "Go to the mountains", "Work"], 1),
    ("Where will Ken go?", ["The sea", "A movie theater", "The mountains", "A friend's house"], 2),
])

dialogue("n5-station", "Where is the station?", 5, "directions", ("男の人", M), ("女の人", F), [
    ("A", "すみません、駅はどこですか。", "Excuse me, where is the station?", ["駅"]),
    ("B", "駅ですか。あの銀行の右です。", "The station? It's to the right of that bank.", ["銀行", "右"]),
    ("A", "遠いですか。", "Is it far?", ["遠い"]),
    ("B", "いいえ、近いですよ。歩いて三分です。", "No, it's close. Three minutes on foot.", ["近い", "三分"]),
    ("A", "どうもありがとうございます。", "Thank you very much.", []),
    ("B", "いいえ。", "Not at all.", []),
], [
    ("Where is the station?", ["Left of the bank", "Right of the bank", "Behind the school", "Next to the park"], 1),
    ("How long does it take to walk?", ["One minute", "Three minutes", "Five minutes", "Ten minutes"], 1),
])

dialogue("n5-family", "My family", 5, "family", ("マリア", FY), ("さとし", MY), [
    ("A", "さとしさんは何人家族ですか。", "Satoshi, how many people are in your family?", ["家族"]),
    ("B", "四人です。父と母と姉がいます。", "Four. I have a father, a mother and an older sister.", ["父", "母", "姉"]),
    ("A", "お姉さんは何歳ですか。", "How old is your sister?", []),
    ("B", "二十五歳です。銀行で働いています。", "Twenty-five. She works at a bank.", ["銀行"]),
    ("A", "そうですか。私は兄弟がいません。", "I see. I don't have any siblings.", ["兄弟"]),
    ("B", "一人っ子ですね。", "So you're an only child.", []),
], [
    ("How many people are in Satoshi's family?", ["Three", "Four", "Five", "Six"], 1),
    ("Where does Satoshi's sister work?", ["A school", "A hospital", "A bank", "A shop"], 2),
])

dialogue("n5-restaurant", "At a restaurant", 5, "food", ("店員", F), ("客", M), [
    ("A", "ご注文は？", "Your order?", []),
    ("B", "カレーを一つお願いします。", "One curry, please.", ["カレー"]),
    ("A", "お飲み物は？", "And to drink?", []),
    ("B", "水をください。", "Water, please.", ["水"]),
    ("A", "カレーは少し辛いですが、大丈夫ですか。", "The curry is a little spicy. Is that okay?", ["辛い", "大丈夫"]),
    ("B", "はい、辛いのが好きです。", "Yes, I like spicy food.", ["好き"]),
], [
    ("What does the customer order to eat?", ["Ramen", "Sushi", "Curry", "Tempura"], 2),
    ("What does the customer drink?", ["Tea", "Water", "Juice", "Beer"], 1),
])

dialogue("n5-birthday", "A birthday", 5, "social", ("あき", FY), ("ジョン", MY), [
    ("A", "ジョンさん、誕生日はいつですか。", "John, when is your birthday?", ["誕生日"]),
    ("B", "六月十日です。あきさんは？", "June 10th. And you, Aki?", ["六月"]),
    ("A", "私は明日です。", "Mine is tomorrow.", ["明日"]),
    ("B", "えっ、明日ですか。おめでとうございます！", "What, tomorrow? Congratulations!", []),
    ("A", "ありがとう。明日、パーティーをします。来ませんか。", "Thanks. I'm having a party tomorrow. Won't you come?", ["パーティー"]),
    ("B", "はい、行きます！", "Yes, I'll come!", []),
], [
    ("When is Aki's birthday?", ["Today", "Tomorrow", "June 10th", "Next week"], 1),
    ("What will John do?", ["Go to the party", "Work", "Stay home", "Travel"], 0),
])

dialogue("n5-library", "At the library", 5, "school", ("学生", MY), ("図書館の人", FO), [
    ("A", "すみません、この本を借りたいです。", "Excuse me, I'd like to borrow this book.", ["本"]),
    ("B", "カードはありますか。", "Do you have a card?", ["カード"]),
    ("A", "はい、これです。", "Yes, here it is.", []),
    ("B", "二週間後に返してください。", "Please return it in two weeks.", ["週間"]),
    ("A", "わかりました。何時まで開いていますか。", "Understood. Until what time are you open?", ["何時"]),
    ("B", "夜七時までです。", "Until seven in the evening.", ["夜"]),
], [
    ("When must the book be returned?", ["In one week", "In two weeks", "In three weeks", "In a month"], 1),
    ("Until what time is the library open?", ["5 p.m.", "6 p.m.", "7 p.m.", "8 p.m."], 2),
])

dialogue("n5-weather", "Tomorrow's weather", 5, "weather", ("母", FO), ("子ども", MY), [
    ("A", "明日は雨ですよ。", "It's going to rain tomorrow.", ["雨"]),
    ("B", "えっ、本当？学校にかさを持っていかないと。", "Really? I have to take an umbrella to school.", ["学校"]),
    ("A", "そうね。午後から雨が降ります。", "Yes. It'll rain from the afternoon.", ["午後"]),
    ("B", "朝は大丈夫？", "Is the morning okay?", ["朝"]),
    ("A", "朝は曇りですよ。", "The morning will be cloudy.", ["曇り"]),
    ("B", "わかった。", "Got it.", []),
], [
    ("When will it start to rain?", ["In the morning", "In the afternoon", "At night", "It won't rain"], 1),
    ("What will the morning be like?", ["Sunny", "Rainy", "Cloudy", "Snowy"], 2),
])

dialogue("n5-phone-number", "Phone number", 5, "numbers", ("山田", M), ("キム", F), [
    ("A", "キムさんの電話番号は何番ですか。", "Kim, what's your phone number?", ["電話番号"]),
    ("B", "〇九〇の一二三四の五六七八です。", "090-1234-5678.", []),
    ("A", "〇九〇の一二三四の五六七八ですね。", "090-1234-5678, right?", []),
    ("B", "はい、そうです。", "Yes, that's right.", []),
    ("A", "じゃあ、今晩電話します。", "Then I'll call you tonight.", ["今晩", "電話"]),
    ("B", "はい、待っています。", "Okay, I'll be waiting.", []),
], [
    ("When will Yamada call?", ["This morning", "This afternoon", "Tonight", "Tomorrow"], 2),
])

dialogue("n5-bus", "Taking the bus", 5, "transport", ("客", F), ("運転手", MO), [
    ("A", "すみません、このバスは病院に行きますか。", "Excuse me, does this bus go to the hospital?", ["バス", "病院"]),
    ("B", "はい、行きますよ。", "Yes, it does.", []),
    ("A", "いくらですか。", "How much is it?", []),
    ("B", "二百三十円です。", "230 yen.", []),
    ("A", "何分ぐらいかかりますか。", "About how many minutes does it take?", []),
    ("B", "十五分ぐらいです。", "About fifteen minutes.", []),
], [
    ("Where does the woman want to go?", ["The station", "The hospital", "The school", "The airport"], 1),
    ("How much is the fare?", ["200 yen", "230 yen", "300 yen", "320 yen"], 1),
])

dialogue("n5-hobby", "Hobbies", 5, "free time", ("ひろ", MY), ("エマ", FY), [
    ("A", "エマさんの趣味は何ですか。", "Emma, what are your hobbies?", ["趣味"]),
    ("B", "料理です。毎日作ります。", "Cooking. I make food every day.", ["料理", "毎日"]),
    ("A", "すごいですね。何が得意ですか。", "Amazing. What are you good at?", ["得意"]),
    ("B", "ケーキが得意です。ひろさんは？", "I'm good at cakes. And you, Hiro?", ["ケーキ"]),
    ("A", "私はテニスが好きです。", "I like tennis.", ["テニス"]),
    ("B", "今度一緒にしましょう。", "Let's play together sometime.", ["今度"]),
], [
    ("What is Emma's hobby?", ["Tennis", "Cooking", "Reading", "Music"], 1),
    ("What is Emma good at making?", ["Bread", "Curry", "Cake", "Sushi"], 2),
])

dialogue("n5-room", "My room", 5, "home", ("先生", F), ("学生", MY), [
    ("A", "あなたの部屋に何がありますか。", "What is in your room?", ["部屋"]),
    ("B", "机といすとベッドがあります。", "There's a desk, a chair and a bed.", ["机", "ベッド"]),
    ("A", "テレビはありますか。", "Is there a TV?", ["テレビ"]),
    ("B", "いいえ、ありません。パソコンで見ます。", "No. I watch on my computer.", ["パソコン"]),
    ("A", "部屋は広いですか。", "Is your room big?", ["広い"]),
    ("B", "いいえ、狭いです。でも、明るいです。", "No, it's small. But it's bright.", ["狭い", "明るい"]),
], [
    ("What is NOT in the student's room?", ["A desk", "A bed", "A TV", "A chair"], 2),
    ("How does the student describe the room?", ["Big and dark", "Small but bright", "Big and bright", "Small and dark"], 1),
])

dialogue("n5-time", "What time is it?", 5, "time", ("男の子", MY), ("お父さん", M), [
    ("A", "お父さん、今何時？", "Dad, what time is it now?", ["今"]),
    ("B", "八時半だよ。", "It's eight thirty.", ["八時"]),
    ("A", "えっ、もう八時半！学校に遅れる！", "What, already 8:30! I'll be late for school!", ["遅れる"]),
    ("B", "今日は土曜日だよ。", "Today is Saturday.", ["土曜日"]),
    ("A", "あ、そうだった。もう少し寝ます。", "Oh, right. I'll sleep a little more.", ["少し"]),
    ("B", "はい、おやすみ。", "Okay, good night.", []),
], [
    ("What time is it?", ["7:30", "8:00", "8:30", "9:00"], 2),
    ("Why doesn't the boy go to school?", ["He is sick", "It is Saturday", "It is a holiday", "He is late"], 1),
])

# ---------------------------------------------------------------- N4
dialogue("n4-lost-wallet", "A lost wallet", 4, "trouble", ("駅員", M), ("客", F), [
    ("A", "どうしましたか。", "What's the matter?", []),
    ("B", "財布をなくしてしまったんです。", "I've lost my wallet.", ["財布"]),
    ("A", "どこでなくしたか覚えていますか。", "Do you remember where you lost it?", ["どこ"]),
    ("B", "たぶん電車の中だと思います。", "I think probably on the train.", ["電車"]),
    ("A", "どんな財布ですか。", "What kind of wallet is it?", []),
    ("B", "黒くて、小さい財布です。中にカードが入っています。", "It's black and small. There are cards inside.", ["黒", "小さい"]),
    ("A", "わかりました。調べますので、少々お待ちください。", "I see. I'll check, so please wait a moment.", ["少々"]),
    ("B", "よろしくお願いします。", "Thank you.", []),
], [
    ("Where does the woman think she lost her wallet?", ["At a shop", "On the train", "At the station", "In a taxi"], 1),
    ("What color is the wallet?", ["Red", "Brown", "Black", "White"], 2),
])

dialogue("n4-cold", "Catching a cold", 4, "health", ("同僚", F), ("ポール", M), [
    ("A", "ポールさん、顔色が悪いですね。", "Paul, you look pale.", ["顔色"]),
    ("B", "昨日から熱があるんです。", "I've had a fever since yesterday.", ["昨日", "熱"]),
    ("A", "病院に行きましたか。", "Did you go to the hospital?", ["病院"]),
    ("B", "いいえ、まだです。仕事が忙しくて。", "No, not yet. Work is busy.", ["仕事"]),
    ("A", "無理をしないほうがいいですよ。今日は早く帰ったらどうですか。", "You shouldn't push yourself. Why not go home early today?", ["無理", "早く"]),
    ("B", "そうですね。課長に話してみます。", "You're right. I'll talk to the section chief.", ["課長"]),
], [
    ("Since when has Paul had a fever?", ["This morning", "Yesterday", "Two days ago", "Last week"], 1),
    ("What does the coworker suggest?", ["Take medicine", "Go home early", "Drink water", "Work harder"], 1),
])

dialogue("n4-moving", "Moving house", 4, "home", ("さくら", FY), ("トム", MY), [
    ("A", "トムさん、引っ越したそうですね。", "Tom, I heard you moved.", []),
    ("B", "ええ、先月引っ越しました。", "Yes, I moved last month.", ["先月"]),
    ("A", "新しいアパートはどうですか。", "How's the new apartment?", ["アパート"]),
    ("B", "駅から近くて便利です。でも、家賃が少し高いです。", "It's close to the station and convenient. But the rent is a bit high.", ["便利", "家賃"]),
    ("A", "近所にスーパーはありますか。", "Is there a supermarket nearby?", ["近所", "スーパー"]),
    ("B", "はい、歩いて五分のところにあります。", "Yes, five minutes' walk away.", []),
    ("A", "今度遊びに行ってもいいですか。", "Can I come over sometime?", ["遊び"]),
    ("B", "もちろん、ぜひ来てください。", "Of course, please do come.", []),
], [
    ("When did Tom move?", ["Last week", "Last month", "Last year", "Yesterday"], 1),
    ("What is the downside of Tom's apartment?", ["Far from the station", "No supermarket", "High rent", "Too small"], 2),
])

dialogue("n4-part-time", "Part-time job", 4, "work", ("店長", M), ("リン", FY), [
    ("A", "リンさん、来週のシフトのことなんですが。", "Lin, it's about next week's shifts.", ["来週"]),
    ("B", "はい。", "Yes.", []),
    ("A", "水曜日に働けますか。", "Can you work on Wednesday?", ["水曜日"]),
    ("B", "すみません、水曜日は授業があるので、ちょっと…。", "Sorry, I have class on Wednesday, so…", ["授業"]),
    ("A", "そうですか。じゃあ、木曜日はどうですか。", "I see. How about Thursday then?", ["木曜日"]),
    ("B", "木曜日なら大丈夫です。", "Thursday is fine.", []),
    ("A", "じゃあ、木曜日の五時からお願いします。", "Then from five on Thursday, please.", []),
], [
    ("Why can't Lin work on Wednesday?", ["She has class", "She is sick", "She is traveling", "She has another job"], 0),
    ("When will Lin work?", ["Wednesday at 5", "Thursday at 5", "Thursday at 3", "Friday at 5"], 1),
])

dialogue("n4-gift", "Choosing a gift", 4, "shopping", ("店員", F), ("客", M), [
    ("A", "何かお探しですか。", "Are you looking for something?", []),
    ("B", "母へのプレゼントを探しているんですが。", "I'm looking for a present for my mother.", ["プレゼント"]),
    ("A", "こちらのハンカチはいかがですか。人気がありますよ。", "How about this handkerchief? It's popular.", ["ハンカチ", "人気"]),
    ("B", "いいですね。ほかの色もありますか。", "Nice. Do you have other colors?", ["色"]),
    ("A", "はい、ピンクと水色がございます。", "Yes, we have pink and light blue.", ["水色"]),
    ("B", "じゃあ、水色をください。プレゼント用に包んでもらえますか。", "Then the light blue one, please. Could you gift-wrap it?", []),
    ("A", "かしこまりました。", "Certainly.", []),
], [
    ("Who is the gift for?", ["His sister", "His mother", "His friend", "His wife"], 1),
    ("Which color does he choose?", ["Pink", "White", "Light blue", "Yellow"], 2),
])

dialogue("n4-trip", "A trip to Kyoto", 4, "travel", ("まい", FY), ("デビッド", M), [
    ("A", "デビッドさん、連休はどこかへ行きましたか。", "David, did you go anywhere over the long weekend?", ["連休"]),
    ("B", "京都へ行きました。", "I went to Kyoto.", ["京都"]),
    ("A", "いいですね。どうでしたか。", "Nice. How was it?", []),
    ("B", "お寺がきれいでしたが、人が多くて大変でした。", "The temples were beautiful, but it was crowded and tiring.", ["お寺", "大変"]),
    ("A", "何か食べましたか。", "Did you eat anything?", []),
    ("B", "湯豆腐を食べました。とてもおいしかったです。", "I had yudofu. It was very good.", ["湯豆腐"]),
    ("A", "私も今度行ってみたいです。", "I'd like to go sometime too.", []),
], [
    ("What was difficult about the trip?", ["The weather", "The crowds", "The food", "The trains"], 1),
    ("What did David eat?", ["Ramen", "Yudofu", "Sushi", "Okonomiyaki"], 1),
])

dialogue("n4-borrow", "Borrowing an umbrella", 4, "daily", ("学生A", MY), ("学生B", FY), [
    ("A", "あ、雨が降ってきた。", "Oh, it's started raining.", ["雨"]),
    ("B", "傘、持ってないの？", "You don't have an umbrella?", ["傘"]),
    ("A", "うん、忘れちゃった。", "No, I forgot it.", []),
    ("B", "じゃあ、これ貸してあげる。私、もう一本あるから。", "Then I'll lend you this. I have another one.", ["一本"]),
    ("A", "本当？ありがとう。明日返すね。", "Really? Thanks. I'll give it back tomorrow.", ["明日"]),
    ("B", "いつでもいいよ。", "Whenever is fine.", []),
], [
    ("Why can the woman lend her umbrella?", ["It isn't raining much", "She has another one", "She is going by car", "She lives nearby"], 1),
])

dialogue("n4-cooking-class", "Cooking class", 4, "free time", ("先生", FO), ("生徒", F), [
    ("A", "今日は肉じゃがを作りましょう。", "Today let's make nikujaga.", ["肉じゃが"]),
    ("B", "はい。まず何をしますか。", "Okay. What do we do first?", ["まず"]),
    ("A", "じゃがいもの皮をむいて、大きめに切ってください。", "Peel the potatoes and cut them into large pieces.", ["じゃがいも", "皮"]),
    ("B", "これくらいでいいですか。", "Is this size okay?", []),
    ("A", "いいですね。次に肉を炒めます。", "Good. Next we fry the meat.", ["次", "肉"]),
    ("B", "砂糖はいつ入れますか。", "When do we add the sugar?", ["砂糖"]),
    ("A", "野菜を入れてから、しょうゆと一緒に入れます。", "After adding the vegetables, together with the soy sauce.", ["野菜", "しょうゆ"]),
], [
    ("What is the first step?", ["Fry the meat", "Peel and cut potatoes", "Add sugar", "Boil water"], 1),
    ("When is sugar added?", ["First", "With the meat", "After the vegetables", "At the end"], 2),
])

dialogue("n4-dentist", "Dentist appointment", 4, "health", ("受付", F), ("患者", M), [
    ("A", "はい、青木歯科です。", "Hello, Aoki Dental.", ["歯科"]),
    ("B", "すみません、予約をしたいんですが。", "Excuse me, I'd like to make an appointment.", ["予約"]),
    ("A", "どうされましたか。", "What seems to be the problem?", []),
    ("B", "奥の歯が痛いんです。", "A back tooth hurts.", ["歯", "痛い"]),
    ("A", "今日の午後四時はいかがですか。", "How about four this afternoon?", ["午後"]),
    ("B", "四時は仕事なので、六時はどうですか。", "I'm at work at four; how about six?", []),
    ("A", "六時ですね。大丈夫です。お名前をお願いします。", "Six, then. That's fine. Your name, please.", []),
], [
    ("What is the man's problem?", ["A headache", "A toothache", "A fever", "A stomachache"], 1),
    ("What time is the appointment?", ["4:00", "5:00", "6:00", "7:00"], 2),
])

dialogue("n4-club", "Joining a club", 4, "school", ("先輩", MY), ("新入生", FY), [
    ("A", "テニス部に入りませんか。", "Won't you join the tennis club?", ["テニス"]),
    ("B", "興味はあるんですが、したことがないんです。", "I'm interested, but I've never played.", ["興味"]),
    ("A", "大丈夫ですよ。初めての人も多いです。", "It's fine. Lots of people are beginners.", ["初めて"]),
    ("B", "練習は週に何回ですか。", "How many times a week is practice?", ["練習"]),
    ("A", "火曜日と金曜日の二回です。", "Twice: Tuesday and Friday.", ["火曜日", "金曜日"]),
    ("B", "じゃあ、一度見学してもいいですか。", "Then may I come watch once?", ["見学"]),
    ("A", "もちろん。金曜日に来てください。", "Of course. Come on Friday.", []),
], [
    ("How often is practice?", ["Once a week", "Twice a week", "Three times a week", "Every day"], 1),
    ("What will the new student do first?", ["Join immediately", "Watch a practice", "Buy a racket", "Nothing"], 1),
])

dialogue("n4-lateness", "Running late", 4, "social", ("えり", FY), ("ジェイ", MY), [
    ("A", "もしもし、ジェイ君？今どこ？", "Hello, Jay? Where are you now?", []),
    ("B", "ごめん、電車が遅れていて、あと二十分ぐらいかかりそう。", "Sorry, the train is delayed; it'll take about twenty more minutes.", ["電車", "二十"]),
    ("A", "そうなんだ。映画は七時からだよ。", "I see. The movie starts at seven.", ["映画", "七時"]),
    ("B", "先にチケットを買っておいてくれる？", "Could you buy the tickets first?", ["チケット"]),
    ("A", "いいよ。飲み物も買っておくね。", "Sure. I'll get drinks too.", ["飲み物"]),
    ("B", "ありがとう。急いで行くね。", "Thanks. I'll hurry.", ["急いで"]),
], [
    ("Why is Jay late?", ["He overslept", "The train is delayed", "He forgot", "He got lost"], 1),
    ("What does Jay ask Eri to do?", ["Wait outside", "Buy the tickets", "Go home", "Call him later"], 1),
])

dialogue("n4-recycling", "Taking out the trash", 4, "home", ("大家", FO), ("住人", M), [
    ("A", "すみません、ごみのことなんですけど。", "Excuse me, it's about the trash.", ["ごみ"]),
    ("B", "はい、何でしょうか。", "Yes, what is it?", []),
    ("A", "燃えるごみは月曜日と木曜日に出してください。", "Please put out burnable trash on Mondays and Thursdays.", ["燃える", "月曜日"]),
    ("B", "あ、すみません。知りませんでした。", "Oh, I'm sorry. I didn't know.", []),
    ("A", "瓶と缶は水曜日です。", "Bottles and cans are Wednesday.", ["瓶", "缶"]),
    ("B", "わかりました。これから気をつけます。", "Understood. I'll be careful from now on.", ["これから"]),
], [
    ("When is burnable trash collected?", ["Mon & Thu", "Tue & Fri", "Wednesday", "Saturday"], 0),
    ("When are bottles and cans collected?", ["Monday", "Tuesday", "Wednesday", "Thursday"], 2),
])

dialogue("n4-homestay", "Homestay dinner", 4, "food", ("お母さん", FO), ("留学生", FY), [
    ("A", "晩ごはんができましたよ。", "Dinner is ready.", ["晩ごはん"]),
    ("B", "わあ、おいしそう！これは何ですか。", "Wow, looks delicious! What is this?", []),
    ("A", "てんぷらです。食べたことがありますか。", "Tempura. Have you had it before?", ["てんぷら"]),
    ("B", "国の日本料理店で一度だけ食べました。", "Only once, at a Japanese restaurant back home.", ["一度"]),
    ("A", "塩で食べてもおいしいですよ。", "It's good with salt too.", ["塩"]),
    ("B", "本当だ、おいしいです！", "It's true, it's delicious!", []),
    ("A", "たくさん食べてくださいね。", "Please eat plenty.", []),
], [
    ("How many times has the student eaten tempura before?", ["Never", "Once", "Twice", "Many times"], 1),
    ("What does the host mother suggest eating it with?", ["Soy sauce", "Salt", "Lemon", "Mayonnaise"], 1),
])

dialogue("n4-computer", "Computer trouble", 4, "work", ("社員", M), ("先輩", F), [
    ("A", "すみません、パソコンが動かなくなってしまいました。", "Excuse me, my computer has stopped working.", ["パソコン"]),
    ("B", "電源は入っていますか。", "Is the power on?", ["電源"]),
    ("A", "はい、でも画面が真っ暗なんです。", "Yes, but the screen is completely black.", ["画面", "真っ暗"]),
    ("B", "一度電源を切って、もう一度つけてみてください。", "Try turning it off and on again.", []),
    ("A", "あ、つきました！", "Oh, it came on!", []),
    ("B", "よかった。また困ったら言ってくださいね。", "Good. Let me know if you have trouble again.", ["また"]),
], [
    ("What was wrong with the computer?", ["It was slow", "The screen was black", "It made noise", "The keyboard broke"], 1),
    ("How was the problem solved?", ["Calling IT", "Restarting it", "Buying a new one", "Changing cables"], 1),
])

dialogue("n4-sports-day", "Sports day", 4, "school", ("父", M), ("娘", FY), [
    ("A", "明日の運動会、何時からだっけ。", "What time does tomorrow's sports day start again?", ["運動会"]),
    ("B", "九時からだよ。お弁当、忘れないでね。", "From nine. Don't forget the bento.", ["お弁当"]),
    ("A", "雨が降ったらどうなるの？", "What happens if it rains?", []),
    ("B", "雨なら来週の土曜日になるって。", "If it rains, it moves to next Saturday.", ["来週"]),
    ("A", "何に出るの？", "What events are you in?", []),
    ("B", "リレーに出るよ。一番になりたいな。", "I'm in the relay. I want to come first.", ["リレー", "一番"]),
], [
    ("What happens if it rains?", ["It's canceled", "It's held indoors", "It moves to next Saturday", "It starts later"], 2),
    ("Which event is the daughter in?", ["Tug of war", "Relay", "Dance", "Long jump"], 1),
])

# ---------------------------------------------------------------- N3
dialogue("n3-meeting-change", "Meeting time change", 3, "work", ("部長", MO), ("社員", F), [
    ("A", "明日の会議だけど、時間を変更してもいいかな。", "About tomorrow's meeting — could we change the time?", ["会議", "変更"]),
    ("B", "はい、何時にしましょうか。", "Sure, what time shall we make it?", []),
    ("A", "午前中に急な出張が入ってしまって。午後三時でどうだろう。", "An urgent business trip came up in the morning. How about 3 p.m.?", ["出張", "急"]),
    ("B", "三時ですね。会議室の予約を変えておきます。", "Three o'clock. I'll change the meeting room booking.", ["会議室", "予約"]),
    ("A", "助かるよ。資料は今日中に送ってもらえる？", "That helps. Can you send me the materials today?", ["資料"]),
    ("B", "はい、夕方までにメールでお送りします。", "Yes, I'll email them by evening.", ["夕方"]),
    ("A", "よろしく頼むね。", "Thanks, I'm counting on you.", []),
], [
    ("Why does the manager want to change the time?", ["He is sick", "He has a sudden business trip", "The room is taken", "A client canceled"], 1),
    ("What will the employee send by evening?", ["The agenda only", "The meeting materials", "A new schedule", "A report on the trip"], 1),
])

dialogue("n3-complaint", "Noise complaint", 3, "home", ("隣人", F), ("住人", M), [
    ("A", "すみません、隣の者ですが、ちょっとよろしいですか。", "Excuse me, I'm your neighbor. Do you have a moment?", ["隣"]),
    ("B", "はい、何でしょう。", "Yes, what is it?", []),
    ("A", "夜遅くに音楽の音が聞こえてきて、なかなか眠れないんです。", "I can hear music late at night, and I can't sleep.", ["音楽"]),
    ("B", "それは申し訳ありません。気がつきませんでした。", "I'm very sorry. I didn't realize.", ["申し訳"]),
    ("A", "十時以降は少し小さくしていただけると助かります。", "It would help if you could turn it down after ten.", ["以降"]),
    ("B", "わかりました。これからはヘッドホンを使うようにします。", "Understood. From now on I'll use headphones.", ["ヘッドホン"]),
    ("A", "ありがとうございます。", "Thank you.", []),
], [
    ("What is the neighbor's complaint?", ["Loud footsteps", "Music at night", "Trash", "A barking dog"], 1),
    ("What does the man decide to do?", ["Move out", "Use headphones", "Stop playing music entirely", "Play only on weekends"], 1),
])

dialogue("n3-interview-prep", "Preparing for an interview", 3, "work", ("先輩", M), ("後輩", FY), [
    ("A", "来週の面接、準備はできてる？", "Are you ready for next week's interview?", ["面接", "準備"]),
    ("B", "志望動機がうまく言えなくて、困っているんです。", "I'm struggling to explain my motivation well.", ["志望動機"]),
    ("A", "その会社のどこに魅力を感じたの？", "What attracted you to that company?", ["魅力"]),
    ("B", "海外との取引が多くて、語学が生かせるところです。", "They do a lot of overseas business, so I could use my language skills.", ["海外", "語学"]),
    ("A", "それをそのまま言えばいいんだよ。具体的な経験を一つ加えるといい。", "Just say that. Add one concrete experience.", ["具体的"]),
    ("B", "なるほど。留学の経験を話してみます。", "I see. I'll talk about my study abroad.", ["留学"]),
    ("A", "がんばってね。", "Good luck.", []),
], [
    ("What is the junior struggling with?", ["Choosing clothes", "Explaining her motivation", "Finding the company", "Writing a résumé"], 1),
    ("What does the senior advise adding?", ["A joke", "A concrete experience", "Salary questions", "Nothing"], 1),
])

dialogue("n3-hospital-visit", "Visiting a friend in hospital", 3, "health", ("なおみ", F), ("けんじ", M), [
    ("A", "けんじさん、具合はどう？", "Kenji, how are you feeling?", ["具合"]),
    ("B", "だいぶよくなったよ。来週には退院できるって。", "Much better. They say I can leave next week.", ["退院"]),
    ("A", "よかった。足の手術、大変だったね。", "That's great. The leg surgery must have been hard.", ["手術"]),
    ("B", "うん、でもリハビリのほうがつらいかな。", "Yeah, but the rehab is tougher.", ["リハビリ"]),
    ("A", "これ、お見舞い。好きな雑誌を持ってきたよ。", "This is for you. I brought your favorite magazine.", ["お見舞い", "雑誌"]),
    ("B", "ありがとう。ちょうど退屈してたんだ。", "Thanks. I was just getting bored.", ["退屈"]),
], [
    ("When can Kenji leave the hospital?", ["Tomorrow", "This weekend", "Next week", "Next month"], 2),
    ("What did Naomi bring?", ["Flowers", "Fruit", "A magazine", "A book"], 2),
])

dialogue("n3-travel-agency", "At a travel agency", 3, "travel", ("係員", F), ("客", M), [
    ("A", "どちらへのご旅行をお考えですか。", "Where are you thinking of traveling?", ["旅行"]),
    ("B", "北海道に三泊四日で行きたいんですが。", "I'd like to go to Hokkaido for four days, three nights.", ["北海道"]),
    ("A", "時期はいつごろでしょうか。", "Around when?", ["時期"]),
    ("B", "二月の雪祭りに合わせて行きたいです。", "I want to go for the snow festival in February.", ["雪祭り"]),
    ("A", "その時期は大変混みますので、早めのご予約をおすすめします。", "That period is very crowded, so we recommend booking early.", ["予約"]),
    ("B", "ホテル付きのプランはありますか。", "Do you have a plan that includes a hotel?", ["ホテル"]),
    ("A", "はい、こちらの飛行機とホテルのセットがお得です。", "Yes, this flight and hotel package is a good deal.", ["飛行機", "お得"]),
], [
    ("Why does the man want to go in February?", ["It's cheaper", "For the snow festival", "For skiing", "For work"], 1),
    ("What does the agent recommend?", ["Going in summer", "Booking early", "Taking the train", "A shorter trip"], 1),
])

dialogue("n3-volunteer", "Volunteering", 3, "social", ("リサ", FY), ("たけし", MY), [
    ("A", "週末、公園のごみ拾いのボランティアに参加するんだけど、一緒にどう？", "I'm joining a park cleanup volunteer event this weekend. Want to come?", ["公園", "ボランティア", "参加"]),
    ("B", "いいね。何時から？", "Sounds good. What time?", []),
    ("A", "朝八時に公園の入り口に集合だよ。", "We meet at the park entrance at 8 a.m.", ["入り口", "集合"]),
    ("B", "早いなあ。何か持っていくものある？", "That's early. Should I bring anything?", []),
    ("A", "軍手と飲み物だけで大丈夫。袋は用意してくれるって。", "Just work gloves and a drink. They'll provide bags.", ["軍手", "用意"]),
    ("B", "わかった。じゃあ、行くよ。", "Got it. I'll come then.", []),
], [
    ("Where do they meet?", ["The station", "The park entrance", "A school", "A café"], 1),
    ("What will the organizers provide?", ["Gloves", "Drinks", "Bags", "Lunch"], 2),
])

dialogue("n3-bank-transfer", "At the bank", 3, "daily", ("行員", F), ("客", M), [
    ("A", "本日はどのようなご用件でしょうか。", "How may I help you today?", ["用件"]),
    ("B", "海外に送金したいんですが。", "I'd like to send money overseas.", ["送金"]),
    ("A", "かしこまりました。身分証明書をお持ちですか。", "Certainly. Do you have identification?", ["身分証明書"]),
    ("B", "在留カードでいいですか。", "Is a residence card okay?", []),
    ("A", "はい、結構です。こちらの用紙にご記入ください。", "Yes, that's fine. Please fill in this form.", ["用紙", "記入"]),
    ("B", "手数料はいくらかかりますか。", "How much is the fee?", ["手数料"]),
    ("A", "一回につき四千円でございます。", "4,000 yen per transfer.", []),
], [
    ("What does the man want to do?", ["Open an account", "Send money overseas", "Exchange currency", "Get a loan"], 1),
    ("How much is the fee?", ["400 yen", "1,000 yen", "4,000 yen", "It's free"], 2),
])

dialogue("n3-environment", "Saving energy", 3, "society", ("先生", M), ("学生", FY), [
    ("A", "今日は環境問題について話しましょう。", "Today let's talk about environmental problems.", ["環境", "問題"]),
    ("B", "私は毎日できることから始めるのが大切だと思います。", "I think it's important to start with things we can do every day.", ["大切"]),
    ("A", "たとえば、どんなことですか。", "For example, what kinds of things?", []),
    ("B", "使わない電気を消したり、マイバッグを持って行ったりすることです。", "Turning off lights we're not using, and bringing our own bags.", ["電気"]),
    ("A", "なるほど。でも、それだけで十分でしょうか。", "I see. But is that alone enough?", ["十分"]),
    ("B", "十分ではありませんが、一人一人の意識が社会を変えると思います。", "It's not enough, but I think each person's awareness changes society.", ["意識", "社会"]),
], [
    ("What does the student think is important?", ["Government action", "Starting with daily actions", "New technology", "Moving to the countryside"], 1),
    ("Does the student think daily actions alone are enough?", ["Yes, completely", "No, but awareness matters", "She doesn't know", "She didn't answer"], 1),
])

dialogue("n3-restaurant-reservation", "Restaurant reservation", 3, "food", ("店員", M), ("客", F), [
    ("A", "お電話ありがとうございます。レストラン葵でございます。", "Thank you for calling. This is Restaurant Aoi.", []),
    ("B", "今週の土曜日、六時から四人で予約をお願いしたいんですが。", "I'd like to book for four at six this Saturday.", ["土曜日", "予約"]),
    ("A", "申し訳ございません。六時は満席でして、七時半でしたらご用意できます。", "I'm sorry, six is fully booked, but we can do seven thirty.", ["満席"]),
    ("B", "七時半ですか…。じゃあ、それでお願いします。", "Seven thirty… Okay, please book that.", []),
    ("A", "かしこまりました。何かアレルギーはございますか。", "Certainly. Any allergies?", ["アレルギー"]),
    ("B", "一人、卵が食べられないんです。", "One person can't eat eggs.", ["卵"]),
    ("A", "承知いたしました。シェフに伝えておきます。", "Understood. I'll let the chef know.", ["シェフ"]),
], [
    ("What time is the reservation?", ["6:00", "6:30", "7:00", "7:30"], 3),
    ("What allergy is mentioned?", ["Shrimp", "Eggs", "Milk", "Wheat"], 1),
])

dialogue("n3-phone-shop", "Buying a smartphone", 3, "shopping", ("店員", MY), ("客", FO), [
    ("A", "機種変更をご希望ですか。", "Are you looking to change your phone model?", ["機種", "希望"]),
    ("B", "ええ、今のは電池がすぐなくなるので。", "Yes, my current one's battery runs out fast.", ["電池"]),
    ("A", "こちらの新しいモデルは、電池が二日ほど持ちます。", "This new model's battery lasts about two days.", ["モデル"]),
    ("B", "文字が大きく表示できるのがいいんですが。", "I'd like one that can show large text.", ["文字", "表示"]),
    ("A", "設定で簡単に大きくできますよ。", "You can easily enlarge it in the settings.", ["設定", "簡単"]),
    ("B", "じゃあ、これにします。データは移せますか。", "Then I'll take this one. Can the data be transferred?", ["データ"]),
    ("A", "はい、こちらで移しますので、三十分ほどお待ちください。", "Yes, we'll do it here; please wait about thirty minutes.", []),
], [
    ("Why does the woman want a new phone?", ["It's broken", "The battery runs out fast", "It's too heavy", "It's old-fashioned"], 1),
    ("How long must she wait?", ["10 minutes", "20 minutes", "30 minutes", "An hour"], 2),
])

dialogue("n3-study-abroad", "Study abroad plans", 3, "school", ("父", MO), ("息子", MY), [
    ("A", "留学のこと、ちゃんと考えたのか。", "Have you thought seriously about studying abroad?", ["留学"]),
    ("B", "うん。一年間、カナダの大学で勉強したいと思ってる。", "Yes. I want to study at a Canadian university for a year.", ["大学"]),
    ("A", "費用はどうするんだ。", "What about the cost?", ["費用"]),
    ("B", "奨学金に申し込むつもりだよ。足りない分はアルバイトでためる。", "I plan to apply for a scholarship, and save the rest from part-time work.", ["奨学金", "アルバイト"]),
    ("A", "そこまで考えているなら、応援するよ。", "If you've thought it through that far, I'll support you.", ["応援"]),
    ("B", "ありがとう、お父さん。", "Thanks, Dad.", []),
], [
    ("How will the son pay for study abroad?", ["His parents will pay", "Scholarship and part-time work", "A bank loan", "He hasn't decided"], 1),
    ("How does the father respond?", ["He refuses", "He supports it", "He wants him to wait", "He's angry"], 1),
])

dialogue("n3-lost-way", "Lost in the city", 3, "directions", ("観光客", F), ("警察官", M), [
    ("A", "すみません、美術館へ行きたいんですが、道に迷ってしまって。", "Excuse me, I want to go to the art museum but I've gotten lost.", ["美術館", "道"]),
    ("B", "美術館なら、この通りを二百メートルほど行った先の交差点を左です。", "For the museum, go about 200 meters down this street and turn left at the intersection.", ["通り", "交差点"]),
    ("A", "交差点を左ですね。そこから遠いですか。", "Left at the intersection. Is it far from there?", []),
    ("B", "いいえ、曲がるとすぐ右側に見えます。", "No, once you turn you'll see it right away on the right.", ["右側"]),
    ("A", "今日は何時まで開いているかご存じですか。", "Do you know until what time it's open today?", []),
    ("B", "たしか五時までだったと思います。", "I believe it's until five.", []),
], [
    ("Where should the tourist turn?", ["Right at the intersection", "Left at the intersection", "At the station", "At the bridge"], 1),
    ("Until what time is the museum open?", ["4:00", "5:00", "6:00", "7:00"], 1),
])

dialogue("n3-overtime", "Overtime", 3, "work", ("同僚A", F), ("同僚B", M), [
    ("A", "最近、毎日残業してるね。大丈夫？", "You've been working overtime every day lately. Are you okay?", ["残業"]),
    ("B", "新しいプロジェクトの締め切りが近くて。", "The new project's deadline is close.", ["プロジェクト", "締め切り"]),
    ("A", "一人で抱え込まないで、手伝えることがあったら言ってね。", "Don't take it all on yourself; tell me if I can help.", ["一人"]),
    ("B", "ありがとう。じゃあ、この資料の確認をお願いしてもいい？", "Thanks. Then could I ask you to check these documents?", ["資料", "確認"]),
    ("A", "もちろん。明日の朝までにやっておくよ。", "Of course. I'll do it by tomorrow morning.", []),
    ("B", "本当に助かる。", "That really helps.", ["助かる"]),
], [
    ("Why is the man working overtime?", ["He's new", "A deadline is near", "His boss is strict", "He wants money"], 1),
    ("What will the woman do?", ["Talk to the boss", "Check the documents", "Work overtime too", "Take his project"], 1),
])

dialogue("n3-earthquake", "Earthquake preparation", 3, "safety", ("母", F), ("娘", FY), [
    ("A", "防災グッズ、ちゃんと準備してある？", "Have you properly prepared your emergency kit?", ["防災", "準備"]),
    ("B", "水と懐中電灯はあるけど、ほかに何が必要かな。", "I have water and a flashlight, but what else do I need?", ["懐中電灯", "必要"]),
    ("A", "三日分の食料と、薬と、携帯の充電器も入れておきなさい。", "Put in three days of food, medicine, and a phone charger too.", ["食料", "充電器"]),
    ("B", "避難場所はどこだっけ。", "Where's the evacuation site again?", ["避難"]),
    ("A", "近くの小学校よ。家族で一度確認しておこうね。", "The nearby elementary school. Let's check it once as a family.", ["小学校"]),
    ("B", "わかった。今度の日曜日に行ってみよう。", "Okay. Let's go this Sunday.", []),
], [
    ("How many days of food should be prepared?", ["One", "Two", "Three", "Seven"], 2),
    ("Where is the evacuation site?", ["City hall", "A park", "An elementary school", "A hospital"], 2),
])

dialogue("n3-customer-return", "Online order problem", 3, "shopping", ("オペレーター", F), ("客", M), [
    ("A", "お問い合わせありがとうございます。", "Thank you for contacting us.", ["問い合わせ"]),
    ("B", "昨日届いた商品が注文したものと違うんです。", "The item that arrived yesterday is different from what I ordered.", ["商品", "注文"]),
    ("A", "大変申し訳ございません。注文番号を教えていただけますか。", "We're very sorry. Could you tell me the order number?", ["番号"]),
    ("B", "A三五七二です。青いシャツを頼んだのに、赤いのが届きました。", "It's A3572. I ordered a blue shirt, but a red one came.", ["シャツ"]),
    ("A", "確認いたしました。すぐに正しい商品をお送りします。", "I've confirmed it. We'll send the correct item right away.", ["正しい"]),
    ("B", "届いた赤いシャツはどうすればいいですか。", "What should I do with the red shirt?", []),
    ("A", "着払いで返送していただければ結構です。", "Please return it cash-on-delivery at our expense.", ["返送"]),
], [
    ("What was wrong with the order?", ["It was broken", "Wrong color", "Wrong size", "It never arrived"], 1),
    ("What will the company do?", ["Refund only", "Send the correct item", "Give a coupon", "Nothing"], 1),
])

dialogue("n3-neighborhood-festival", "Neighborhood festival", 3, "social", ("町内会長", MO), ("新住民", F), [
    ("A", "来月、町内のお祭りがあるんですが、手伝っていただけませんか。", "There's a neighborhood festival next month. Could you help out?", ["町内", "祭り"]),
    ("B", "ぜひ。どんなことをすればいいですか。", "Gladly. What should I do?", []),
    ("A", "焼きそばの屋台をお願いしたいんです。", "We'd like you to help at the yakisoba stall.", ["焼きそば", "屋台"]),
    ("B", "料理はあまり得意じゃないんですが…。", "I'm not very good at cooking, though…", ["得意"]),
    ("A", "大丈夫ですよ。ほかの人が作るので、お金を受け取る係です。", "That's fine. Others will cook; you'd handle the money.", ["係"]),
    ("B", "それならできます。楽しみです。", "Then I can do it. I'm looking forward to it.", ["楽しみ"]),
], [
    ("What is the woman asked to do?", ["Cook yakisoba", "Handle money at a stall", "Dance", "Put up decorations"], 1),
    ("When is the festival?", ["This weekend", "Next week", "Next month", "Next year"], 2),
])


def main() -> None:
    out = Path(__file__).resolve().parent / "dialogues.json"
    doc = {
        "source": "llm",
        "note": "Dialogues drafted by an LLM; review with tools/items/review.py before marking verified.",
        "dialogues": D,
    }
    out.write_text(json.dumps(doc, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    print(f"{len(D)} dialogues")


if __name__ == "__main__":
    main()
