"""Authoring source for tools/packs/listening/dialogues.json (LLM-drafted, source="llm", awaiting human review).

Merge (re-runnable; never duplicates ids, keeps reviewed entries; see packs/practice_authoring.py):
    uv run python packs/listening/author_dialogues.py
Draft more through an OpenAI-compatible endpoint (e.g. Ollama on the owner's GPU machine), appended to
batches/llm-drafts.json and merged:
    uv run python packs/listening/author_dialogues.py draft --endpoint http://<lan-ip>:11434/v1 \\
        --model qwen2.5:14b --level 3 --style natural --count 5

Sources: the dialogues below, then batches/*.json (same entry format as dialogues.json, documented in
docs/CONTENT_PACKS.md "Practice pack"). Line: (speaker id, japanese, english, [gap words — verbatim in the line,
JMdict words]). Question: (english question, [choices], answer index). Style "natural" lines mark fillers and
restarts with {braces}.
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import practice_authoring as pa

D = []

F = ("female", "adult")
M = ("male", "adult")
FY = ("female", "young")
MY = ("male", "young")
FO = ("female", "senior")
MO = ("male", "senior")


def dialogue(id_, title, jlpt, topic, a, b, lines, questions):
    D.append({
        "id": id_, "title": title, "jlpt": jlpt, "topic": topic, "style": "scripted",
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
    ("B", "いいえ、まだです。今朝は時間がありませんでした。", "No, not yet. I didn't have time this morning.", ["まだ", "時間"]),
    ("A", "じゃあ、一緒にパンを食べませんか。コーヒーもありますよ。", "Then shall we eat some bread together? There's coffee too.", ["一緒", "パン"]),
    ("B", "ありがとうございます。でも、コーヒーはちょっと…。お茶はありますか。", "Thank you. But coffee is a bit… Do you have tea?", ["お茶"]),
    ("A", "はい、ありますよ。温かいお茶をどうぞ。", "Yes, I do. Here's some hot tea.", ["温かい"]),
    ("B", "ありがとうございます。いただきます。", "Thank you. I'll have some.", []),
], [
    ("Why hasn't Lee eaten breakfast yet?", [
        "It was too cold to go out", "He was waiting to eat with Tanaka",
        "He was short of time this morning", "He only has coffee in the morning"], 2),
    ("What does Lee end up having?", [
        "Bread with coffee", "Bread with hot tea", "Only a cup of coffee", "Only a cup of hot tea"], 1),
])

dialogue("n5-shop-apples", "Buying apples", 5, "shopping", ("店員", MO), ("客", F), [
    ("A", "いらっしゃいませ。", "Welcome.", []),
    ("B", "すみません、このりんごはいくらですか。", "Excuse me, how much are these apples?", ["りんご"]),
    ("A", "一つ百円です。みかんは二つで百円ですよ。", "They're 100 yen each. The mandarins are two for 100 yen.", ["百", "みかん"]),
    ("B", "安いですね。じゃあ、りんごを三つと、みかんを四つください。", "That's cheap. Then three apples and four mandarins, please.", ["安い"]),
    ("A", "はい、全部で五百円です。", "Okay, that's 500 yen altogether.", ["全部"]),
    ("B", "はい、どうぞ。", "Here you are.", []),
    ("A", "ありがとうございました。", "Thank you very much.", []),
], [
    ("How much does one mandarin cost?", ["100 yen", "50 yen", "200 yen", "500 yen"], 1),
    ("What does the customer buy?", [
        "Four apples and three mandarins", "Three apples and two mandarins",
        "Only three apples", "Three apples and four mandarins"], 3),
    ("How much of the total is for the apples?", ["100 yen", "200 yen", "300 yen", "400 yen"], 2),
])

dialogue("n5-weekend", "Weekend plans", 5, "free time", ("ケン", MY), ("ゆき", FY), [
    ("A", "ゆきさん、週末は何をしますか。", "Yuki, what are you doing this weekend?", ["週末"]),
    ("B", "土曜日に友達と映画を見ます。", "On Saturday I'm seeing a movie with a friend.", ["土曜日", "映画"]),
    ("A", "いいですね。日曜日は？", "Nice. And Sunday?", ["日曜日"]),
    ("B", "日曜日は家で勉強します。月曜日にテストがありますから。ケンさんは？", "On Sunday I'll study at home. I have a test on Monday. And you, Ken?", ["勉強", "テスト"]),
    ("A", "私は日曜日に山に行きます。ゆきさんも一緒に行きませんか。", "I'm going to the mountains on Sunday. Won't you come too, Yuki?", ["山", "一緒"]),
    ("B", "行きたいですが、今回はちょっと…。", "I'd like to, but this time is a bit…", ["今回"]),
    ("A", "そうですか。じゃあ、また今度。", "I see. Next time, then.", ["今度"]),
    ("B", "はい、今度はぜひ。", "Yes, next time for sure.", []),
], [
    ("Why doesn't Yuki go to the mountains with Ken?", [
        "She's at the movies with a friend that day", "She has to work on Monday",
        "Ken is going on Saturday, when she's busy", "She's staying home to prepare for a test"], 3),
    ("What will Ken do on Sunday?", [
        "Study at home", "Go to the mountains", "See a movie with Yuki", "Take a test"], 1),
])

dialogue("n5-station", "Where is the station?", 5, "directions", ("男の人", M), ("女の人", F), [
    ("A", "すみません、駅はどこですか。", "Excuse me, where is the station?", ["駅"]),
    ("B", "駅ですか。あの銀行の右です。あ、すみません、左です。", "The station? It's to the right of that bank. Oh, sorry, to the left.", ["銀行", "左"]),
    ("A", "銀行の左ですね。遠いですか。バスで行きますか。", "To the left of the bank. Is it far? Should I take a bus?", ["遠い", "バス"]),
    ("B", "いいえ、近いですよ。歩いて三分です。", "No, it's close. Three minutes on foot.", ["近い", "三分"]),
    ("A", "どうもありがとうございます。", "Thank you very much.", []),
    ("B", "いいえ。", "Not at all.", []),
], [
    ("Where is the station in the end?", [
        "On the right side of the bank", "Across the road from the bank",
        "On the left side of the bank", "Three bus stops past the bank"], 2),
    ("How should the man get there?", [
        "Walk; it only takes a few minutes", "Take the bus, because it's far",
        "Take the bus for three minutes", "Walk for about thirty minutes"], 0),
])

dialogue("n5-family", "My family", 5, "family", ("マリア", FY), ("さとし", MY), [
    ("A", "さとしさんは何人家族ですか。", "Satoshi, how many people are in your family?", ["家族"]),
    ("B", "四人です。父と母と姉がいます。", "Four. I have a father, a mother and an older sister.", ["父", "母", "姉"]),
    ("A", "お姉さんは何歳ですか。", "How old is your sister?", []),
    ("B", "二十五歳です。銀行で働いています。", "Twenty-five. She works at a bank.", ["銀行"]),
    ("A", "そうですか。私は兄弟がいません。", "I see. I don't have any siblings.", ["兄弟"]),
    ("B", "一人っ子ですね。", "So you're an only child.", []),
], [
    ("Who is in Satoshi's family besides him?", [
        "His parents and an older brother", "His mother and two sisters",
        "His parents and an older sister", "His parents, a sister and a brother"], 2),
    ("What do we learn about Maria?", [
        "She has no brothers or sisters", "She has an older sister at a bank",
        "She is twenty-five years old", "She has a family of four"], 0),
])

dialogue("n5-restaurant", "At a restaurant", 5, "food", ("店員", F), ("客", M), [
    ("A", "ご注文は？", "Your order?", []),
    ("B", "ラーメンを一つお願いします。", "One ramen, please.", ["ラーメン"]),
    ("A", "すみません、今日はラーメンがありません。", "I'm sorry, we don't have ramen today.", ["今日"]),
    ("B", "そうですか。じゃあ、カレーをお願いします。", "I see. Then curry, please.", ["カレー"]),
    ("A", "カレーは少し辛いですが、大丈夫ですか。", "The curry is a little spicy. Is that okay?", ["辛い", "大丈夫"]),
    ("B", "はい、辛いのが好きです。それから、水をください。", "Yes, I like spicy food. And water, please.", ["好き", "水"]),
    ("A", "はい、かしこまりました。", "Certainly.", []),
], [
    ("Why does the customer order curry?", [
        "He was told the curry isn't spicy", "The server said the ramen is spicy",
        "The ramen isn't available today", "It comes with a glass of water"], 2),
    ("What does the server check with him?", [
        "Whether a slightly spicy dish is okay", "Whether he wants ramen tomorrow instead",
        "Whether he wants something to drink", "Whether one curry is enough"], 0),
])

dialogue("n5-birthday", "A birthday", 5, "social", ("あき", FY), ("ジョン", MY), [
    ("A", "ジョンさん、誕生日はいつですか。", "John, when is your birthday?", ["誕生日"]),
    ("B", "六月十日です。あきさんは？", "June 10th. And you, Aki?", ["六月"]),
    ("A", "私は明日です。", "Mine is tomorrow.", ["明日"]),
    ("B", "えっ、明日ですか。おめでとうございます！", "What, tomorrow? Congratulations!", []),
    ("A", "ありがとう。明日、家でパーティーをします。来ませんか。", "Thanks. I'm having a party at home tomorrow. Won't you come?", ["家", "パーティー"]),
    ("B", "はい、行きます！何時からですか。", "Yes, I'll come! What time does it start?", ["何時"]),
    ("A", "六時からです。", "From six.", []),
], [
    ("Whose birthday is celebrated at tomorrow's party?", [
        "John's", "Aki's", "Both of theirs", "A friend's born on June 10th"], 1),
    ("What will John do tomorrow?", [
        "Have a party at his house at six", "Go to Aki's house on June 10th",
        "Go to Aki's party at six", "Give Aki a present at school"], 2),
])

dialogue("n5-library", "At the library", 5, "school", ("学生", MY), ("図書館の人", FO), [
    ("A", "すみません、この本を借りたいです。", "Excuse me, I'd like to borrow this book.", ["本"]),
    ("B", "カードはありますか。", "Do you have a card?", ["カード"]),
    ("A", "はい、これです。", "Yes, here it is.", []),
    ("B", "二週間後に返してください。", "Please return it in two weeks.", ["週間"]),
    ("A", "わかりました。何時まで開いていますか。", "Understood. Until what time are you open?", ["何時"]),
    ("B", "平日は夜七時までです。土曜日と日曜日は五時までです。", "Until seven in the evening on weekdays. Until five on Saturdays and Sundays.", ["平日", "夜"]),
    ("A", "じゃあ、土曜日に返しに来ます。", "Then I'll come on a Saturday to return it.", ["土曜日"]),
    ("B", "はい。五時までに来てくださいね。", "Okay. Please come before five.", []),
], [
    ("When does the library close on weekends?", [
        "At seven", "At two", "It is closed on weekends", "At five"], 3),
    ("What must the student remember when he returns the book?", [
        "Come before 5 p.m. on Saturday", "Come before 7 p.m. on Saturday",
        "Return it within one week", "Bring a new library card"], 0),
])

dialogue("n5-weather", "Tomorrow's weather", 5, "weather", ("母", FO), ("子ども", MY), [
    ("A", "明日は雨ですよ。", "It's going to rain tomorrow.", ["雨"]),
    ("B", "えっ、本当？明日は公園でサッカーをするよ。", "Really? I'm playing soccer in the park tomorrow.", ["公園", "サッカー"]),
    ("A", "午後から雨が降りますよ。", "It'll rain from the afternoon.", ["午後"]),
    ("B", "朝は大丈夫？", "Is the morning okay?", ["朝"]),
    ("A", "朝は曇りですよ。", "The morning will be cloudy.", ["曇り"]),
    ("B", "じゃあ、朝早く行くよ。かさも持っていくね。", "Then I'll go early in the morning. I'll take an umbrella too.", ["早く"]),
    ("A", "そうね。", "Good idea.", []),
], [
    ("What will tomorrow's weather be like?", [
        "Rain in the morning, then cloudy", "Rain all day long",
        "Cloudy all day", "Cloudy in the morning, then rain"], 3),
    ("What does the boy decide to do?", [
        "Play soccer in the morning before the rain", "Play soccer in the afternoon",
        "Stay home instead of going to the park", "Go to the park after it rains"], 0),
])

dialogue("n5-phone-number", "Phone number", 5, "numbers", ("山田", M), ("キム", F), [
    ("A", "キムさんの電話番号は何番ですか。", "Kim, what's your phone number?", ["電話番号"]),
    ("B", "〇九〇の一二三四の五六七八です。", "090-1234-5678.", []),
    ("A", "〇九〇の一二三四の五六七九ですね。", "090-1234-5679, right?", []),
    ("B", "いいえ、最後は八です。", "No, the last one is eight.", ["最後"]),
    ("A", "あ、すみません。じゃあ、今晩電話します。", "Oh, sorry. Then I'll call you tonight.", ["今晩", "電話"]),
    ("B", "今晩はアルバイトがあります。明日の朝はどうですか。", "I have my part-time job tonight. How about tomorrow morning?", ["アルバイト", "朝"]),
    ("A", "わかりました。明日の朝、電話します。", "Okay. I'll call you tomorrow morning.", []),
    ("B", "はい、待っています。", "Okay, I'll be waiting.", []),
], [
    ("What mistake does Yamada make?", [
        "He mixes up 1234 and 5678", "He says nine instead of eight at the end",
        "He forgets the 090 at the start", "He says eight instead of nine at the end"], 1),
    ("When will Yamada call Kim?", [
        "Tonight", "Tonight, after her job", "Tomorrow morning", "Tomorrow night"], 2),
])

dialogue("n5-bus", "Taking the bus", 5, "transport", ("客", F), ("運転手", MO), [
    ("A", "すみません、このバスは病院に行きますか。", "Excuse me, does this bus go to the hospital?", ["バス", "病院"]),
    ("B", "いいえ、行きません。病院は三番のバスですよ。", "No, it doesn't. For the hospital it's the number 3 bus.", ["病院"]),
    ("A", "三番のバスはどこですか。", "Where is the number 3 bus?", []),
    ("B", "あそこです。あ、今来ましたよ。", "Over there. Oh, it's just come.", ["今"]),
    ("A", "いくらですか。", "How much is it?", []),
    ("B", "二百三十円です。十五分ぐらいかかります。", "230 yen. It takes about fifteen minutes.", []),
    ("A", "ありがとうございます。", "Thank you.", []),
], [
    ("What does the woman need to do?", [
        "Stay on this bus", "Wait fifteen minutes for the next bus",
        "Get off at the third stop", "Change to a different bus"], 3),
    ("What is the ride to the hospital like?", [
        "330 yen and about fifteen minutes", "230 yen and about three minutes",
        "About fifteen minutes for 230 yen", "About thirty minutes for 230 yen"], 2),
])

dialogue("n5-hobby", "Hobbies", 5, "free time", ("ひろ", MY), ("エマ", FY), [
    ("A", "エマさんの趣味は何ですか。", "Emma, what are your hobbies?", ["趣味"]),
    ("B", "料理です。毎日作ります。", "Cooking. I make food every day.", ["料理", "毎日"]),
    ("A", "すごいですね。何が得意ですか。", "Amazing. What are you good at?", ["得意"]),
    ("B", "ケーキが得意です。ひろさんは？", "I'm good at cakes. And you, Hiro?", ["ケーキ"]),
    ("A", "私はテニスが好きです。料理は下手です。", "I like tennis. I'm bad at cooking.", ["テニス", "下手"]),
    ("B", "私もテニスがしたいです。今度一緒にしましょう。", "I want to play tennis too. Let's play together sometime.", ["今度", "一緒"]),
    ("A", "いいですね。じゃあ、ケーキも持ってきてください。", "Great. Then please bring a cake too.", []),
    ("B", "はい、わかりました！", "Okay, will do!", []),
], [
    ("What do they plan to do?", [
        "Cook together at Emma's", "Play tennis, and Emma brings a cake",
        "Hiro bakes a cake for Emma", "Emma teaches Hiro to cook"], 1),
    ("What do we learn about Hiro?", [
        "He plays tennis every day", "He is good at making cakes",
        "He cooks every day", "He enjoys tennis but can't cook well"], 3),
])

dialogue("n5-room", "My room", 5, "home", ("先生", F), ("学生", MY), [
    ("A", "あなたの部屋に何がありますか。", "What is in your room?", ["部屋"]),
    ("B", "机といすとベッドがあります。", "There's a desk, a chair and a bed.", ["机", "ベッド"]),
    ("A", "テレビはありますか。", "Is there a TV?", ["テレビ"]),
    ("B", "いいえ、ありません。パソコンで見ます。", "No. I watch on my computer.", ["パソコン"]),
    ("A", "部屋は広いですか。", "Is your room big?", ["広い"]),
    ("B", "いいえ、狭いです。でも、明るいです。", "No, it's small. But it's bright.", ["狭い", "明るい"]),
], [
    ("What does the student say about TV?", [
        "He watches TV in bed", "His TV is on his desk",
        "He has no TV and uses his computer", "He has a TV but it is small"], 2),
    ("How does the student describe the room?", [
        "Big, with lots of light", "Small, but it gets a lot of light",
        "Small, with no room for a bed", "Big, but there's no TV"], 1),
])

dialogue("n5-time", "What time is it?", 5, "time", ("男の子", MY), ("お父さん", M), [
    ("A", "お父さん、今何時？", "Dad, what time is it now?", ["今"]),
    ("B", "八時半だよ。", "It's eight thirty.", ["八時"]),
    ("A", "えっ、もう八時半！学校に遅れる！", "What, already 8:30! I'll be late for school!", ["遅れる"]),
    ("B", "今日は土曜日だよ。", "Today is Saturday.", ["土曜日"]),
    ("A", "あ、そうだった。もう少し寝ます。", "Oh, right. I'll sleep a little more.", ["少し"]),
    ("B", "はい、おやすみ。", "Okay, good night.", []),
], [
    ("Why does the boy panic?", [
        "His father woke him too early", "He thinks he'll be late for school",
        "He forgot it was Saturday's event", "He slept past eight thirty on Saturday"], 1),
    ("What does the boy do in the end?", [
        "Hurries off to school", "Gets up because it's already 8:30",
        "Goes back to sleep for a while", "Goes to school for Saturday class"], 2),
])

# ---------------------------------------------------------------- N4
dialogue("n4-lost-wallet", "A lost wallet", 4, "trouble", ("駅員", M), ("客", F), [
    ("A", "どうしましたか。", "What's the matter?", []),
    ("B", "財布をなくしてしまったんです。", "I've lost my wallet.", ["財布"]),
    ("A", "どこでなくしたか覚えていますか。", "Do you remember where you lost it?", ["どこ"]),
    ("B", "駅の売店でお茶を買って、その後で電車に乗りました。たぶん電車の中だと思います。", "I bought tea at the station kiosk and then got on the train. I think probably on the train.", ["売店", "電車"]),
    ("A", "どんな財布ですか。", "What kind of wallet is it?", []),
    ("B", "黒くて、小さい財布です。中にカードが入っています。", "It's black and small. There are cards inside.", ["黒", "小さい"]),
    ("A", "わかりました。調べますので、少々お待ちください。", "I see. I'll check, so please wait a moment.", ["少々"]),
    ("B", "よろしくお願いします。", "Thank you.", []),
], [
    ("Where does the woman think she lost her wallet?", [
        "At the kiosk in the station", "On the way to the station",
        "At the station office", "On the train she took after buying tea"], 3),
    ("Which description matches her wallet?", [
        "Small and black, with cards in it", "Large and black, with cards in it",
        "Small and black, with only cash in it", "Small and brown, with cards in it"], 0),
])

dialogue("n4-cold", "Catching a cold", 4, "health", ("同僚", F), ("ポール", M), [
    ("A", "ポールさん、顔色が悪いですね。", "Paul, you look pale.", ["顔色"]),
    ("B", "昨日から熱があるんです。", "I've had a fever since yesterday.", ["昨日", "熱"]),
    ("A", "病院に行きましたか。", "Did you go to the hospital?", ["病院"]),
    ("B", "いいえ、まだです。仕事が忙しくて。", "No, not yet. Work is busy.", ["仕事"]),
    ("A", "無理をしないほうがいいですよ。今日は早く帰ったらどうですか。", "You shouldn't push yourself. Why not go home early today?", ["無理", "早く"]),
    ("B", "そうですね。課長に話してみます。", "You're right. I'll talk to the section chief.", ["課長"]),
], [
    ("Why hasn't Paul seen a doctor?", [
        "His fever only started this morning", "He has had too much work",
        "The section chief told him to stay", "He feels better than yesterday"], 1),
    ("What will Paul do next?", [
        "Go straight to the hospital", "Keep working until the end of the day",
        "Go home without telling anyone", "Ask his boss about leaving early"], 3),
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
    ("How does Tom feel about his new place?", [
        "It's cheap, but far from the station", "It's near the station, but no shops are close",
        "It's handy, but a little expensive", "It's small, and the rent is high"], 2),
    ("What does Sakura ask to do?", [
        "Visit Tom's apartment one day", "Move into Tom's neighborhood",
        "Go to the supermarket with Tom", "Help Tom move next month"], 0),
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
    ("Why does Lin turn down the first day?", [
        "She already works on Thursday", "The shop is busy on Wednesday",
        "She can only start at five", "She has school that day"], 3),
    ("What do they finally agree on?", [
        "Wednesday from five", "Thursday from five",
        "Wednesday after her class", "Thursday and Wednesday"], 1),
])

dialogue("n4-gift", "Choosing a gift", 4, "shopping", ("店員", F), ("客", M), [
    ("A", "何かお探しですか。", "Are you looking for something?", []),
    ("B", "母へのプレゼントを探しているんですが。", "I'm looking for a present for my mother.", ["プレゼント"]),
    ("A", "こちらのハンカチはいかがですか。人気がありますよ。", "How about this handkerchief? It's popular.", ["ハンカチ", "人気"]),
    ("B", "いいですね。ほかの色もありますか。", "Nice. Do you have other colors?", ["色"]),
    ("A", "はい、ピンクと水色がございます。", "Yes, we have pink and light blue.", ["水色"]),
    ("B", "母は青が好きなので、水色をください。プレゼント用に包んでもらえますか。", "My mother likes blue, so the light blue one, please. Could you gift-wrap it?", ["青"]),
    ("A", "かしこまりました。", "Certainly.", []),
], [
    ("Why does the man choose light blue?", [
        "The clerk says it's the popular one", "His mother is fond of blue",
        "The pink one is sold out", "It's the only other color"], 1),
    ("What does the man ask the clerk to do?", [
        "Show him a pink one as well", "Find a different present",
        "Wrap it as a gift", "Suggest something popular"], 2),
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
    ("What was the hard part of David's trip?", [
        "The temples were disappointing", "The food wasn't to his taste",
        "The long weekend was too short", "There were crowds everywhere"], 3),
    ("How does Mai react to David's story?", [
        "She wants to try going there herself", "She says she went there too",
        "She asks him to take her next time", "She wants to cook yudofu"], 0),
])

dialogue("n4-borrow", "Borrowing an umbrella", 4, "daily", ("学生A", MY), ("学生B", FY), [
    ("A", "あ、雨が降ってきた。", "Oh, it's started raining.", ["雨"]),
    ("B", "傘、持ってないの？", "You don't have an umbrella?", ["傘"]),
    ("A", "うん、忘れちゃった。", "No, I forgot it.", []),
    ("B", "じゃあ、これ貸してあげる。私、もう一本あるから。", "Then I'll lend you this. I have another one.", ["一本"]),
    ("A", "本当？ありがとう。明日返すね。", "Really? Thanks. I'll give it back tomorrow.", ["明日"]),
    ("B", "いつでもいいよ。", "Whenever is fine.", []),
], [
    ("Why can the woman lend her umbrella?", [
        "It has almost stopped raining", "She has a second one",
        "He can return it tomorrow", "She forgot hers at home"], 1),
    ("What does the woman say about getting it back?", [
        "He must bring it back tomorrow", "He can keep the second one",
        "There's no hurry at all", "He should return it when it stops raining"], 2),
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
    ("What does the teacher ask the student to do first?", [
        "Fry the meat", "Cut the potatoes into small pieces",
        "Peel the potatoes and cut them big", "Mix the sugar and soy sauce"], 2),
    ("When does the sugar go in?", [
        "Along with the soy sauce, once the vegetables are in", "Right after the meat starts frying",
        "Before the vegetables, on its own", "At the very start, with the potatoes"], 0),
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
    ("Why can't the man come at the first time offered?", [
        "The clinic is full then", "His tooth hurts too much to wait",
        "He will be at work", "He has another appointment at six"], 2),
    ("What is decided in the end?", [
        "He comes at six today", "He comes at four today",
        "He calls back after work", "He comes tomorrow afternoon"], 0),
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
    ("Why does the new student hesitate?", [
        "She isn't interested in tennis", "Practice is on too many days",
        "She has never played before", "Friday is inconvenient for her"], 2),
    ("What will she do on Friday?", [
        "Join the club officially", "Just watch a practice",
        "Play in her first practice", "Decide between Tuesday and Friday"], 1),
])

dialogue("n4-lateness", "Running late", 4, "social", ("えり", FY), ("ジェイ", MY), [
    ("A", "もしもし、ジェイ君？今どこ？", "Hello, Jay? Where are you now?", []),
    ("B", "ごめん、電車が遅れていて、あと二十分ぐらいかかりそう。", "Sorry, the train is delayed; it'll take about twenty more minutes.", ["電車", "二十"]),
    ("A", "そうなんだ。映画は七時からだよ。", "I see. The movie starts at seven.", ["映画", "七時"]),
    ("B", "先にチケットを買っておいてくれる？", "Could you buy the tickets first?", ["チケット"]),
    ("A", "いいよ。飲み物も買っておくね。", "Sure. I'll get drinks too.", ["飲み物"]),
    ("B", "ありがとう。急いで行くね。", "Thanks. I'll hurry.", ["急いで"]),
], [
    ("Why is Jay late?", [
        "He thought the movie started later", "He missed his train",
        "His train isn't running on time", "He stopped to buy drinks"], 2),
    ("Who will do what before the movie?", [
        "Eri gets the tickets and the drinks", "Eri gets the tickets; Jay brings drinks",
        "Jay gets the tickets when he arrives", "Jay gets the drinks; Eri waits outside"], 0),
])

dialogue("n4-recycling", "Taking out the trash", 4, "home", ("大家", FO), ("住人", M), [
    ("A", "すみません、ごみのことなんですけど。", "Excuse me, it's about the trash.", ["ごみ"]),
    ("B", "はい、何でしょうか。", "Yes, what is it?", []),
    ("A", "今朝、燃えるごみの日に缶が出ていましたよ。", "This morning, cans were put out on burnable-trash day.", ["今朝", "缶"]),
    ("B", "あ、すみません。月曜日は何でも出せると思っていました。", "Oh, I'm sorry. I thought you could put out anything on Mondays.", ["月曜日"]),
    ("A", "燃えるごみは月曜日と木曜日です。瓶と缶は水曜日に出してください。", "Burnable trash is Mondays and Thursdays. Please put out bottles and cans on Wednesday.", ["燃える", "瓶"]),
    ("B", "わかりました。じゃあ、この缶は明後日出します。", "Understood. Then I'll put these cans out the day after tomorrow.", ["明後日"]),
    ("A", "はい、お願いします。", "Yes, please do.", []),
], [
    ("What did the resident do wrong?", [
        "He put out burnable trash on Wednesday", "He put out cans on a burnable-trash day",
        "He forgot to put out the trash on Monday", "He mixed bottles in with the cans"], 1),
    ("When will he put the cans out now?", [
        "On Thursday, with the burnable trash", "Next Monday",
        "Again this morning", "In two days, on Wednesday"], 3),
])

dialogue("n4-homestay", "Homestay dinner", 4, "food", ("お母さん", FO), ("留学生", FY), [
    ("A", "晩ごはんができましたよ。", "Dinner is ready.", ["晩ごはん"]),
    ("B", "わあ、おいしそう！これは何ですか。", "Wow, looks delicious! What is this?", []),
    ("A", "てんぷらです。食べたことがありますか。", "Tempura. Have you had it before?", ["てんぷら"]),
    ("B", "国の日本料理店で一度だけ食べました。", "Only once, at a Japanese restaurant back home.", ["一度"]),
    ("A", "しょうゆもいいですが、塩で食べてもおいしいですよ。", "Soy sauce is fine too, but it's also good with salt.", ["塩"]),
    ("B", "本当だ、おいしいです！", "It's true, it's delicious!", []),
    ("A", "たくさん食べてくださいね。", "Please eat plenty.", []),
], [
    ("What experience has the student had with tempura?", [
        "She has never eaten it before", "She has made it at home",
        "She had it one time in her own country", "She eats it often at restaurants"], 2),
    ("What does the host mother suggest?", [
        "Trying it with salt", "Eating it only with soy sauce",
        "Eating it the way they do in her country", "Saving some for tomorrow"], 0),
])

dialogue("n4-computer", "Computer trouble", 4, "work", ("社員", M), ("先輩", F), [
    ("A", "すみません、パソコンが動かなくなってしまいました。", "Excuse me, my computer has stopped working.", ["パソコン"]),
    ("B", "電源は入っていますか。", "Is the power on?", ["電源"]),
    ("A", "はい、でも画面が真っ暗なんです。", "Yes, but the screen is completely black.", ["画面", "真っ暗"]),
    ("B", "一度電源を切って、もう一度つけてみてください。", "Try turning it off and on again.", []),
    ("A", "あ、つきました！", "Oh, it came on!", []),
    ("B", "よかった。また困ったら言ってくださいね。", "Good. Let me know if you have trouble again.", ["また"]),
], [
    ("What was the problem?", [
        "It wouldn't turn on at all", "The power was on but nothing showed",
        "The power cable had come out", "It kept turning itself off"], 1),
    ("How was it fixed?", [
        "The senior repaired it herself", "He plugged the power back in",
        "He switched it off and back on", "He asked someone else for help"], 2),
])

dialogue("n4-sports-day", "Sports day", 4, "school", ("父", M), ("娘", FY), [
    ("A", "明日の運動会、何時からだっけ。", "What time does tomorrow's sports day start again?", ["運動会"]),
    ("B", "九時からだよ。お弁当、忘れないでね。", "From nine. Don't forget the bento.", ["お弁当"]),
    ("A", "雨が降ったらどうなるの？", "What happens if it rains?", []),
    ("B", "雨なら来週の土曜日になるって。", "If it rains, it moves to next Saturday.", ["来週"]),
    ("A", "何に出るの？", "What events are you in?", []),
    ("B", "リレーに出るよ。一番になりたいな。", "I'm in the relay. I want to come first.", ["リレー", "一番"]),
], [
    ("What happens if it rains tomorrow?", [
        "It is cancelled", "It starts later than nine",
        "It is held a week later, on Saturday", "It moves to next Sunday"], 2),
    ("What does the daughter ask her father to do?", [
        "Remember to bring lunch", "Come at nine to watch the relay",
        "Check the weather tonight", "Cheer so she comes first"], 0),
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
    ("Why is the meeting being moved?", [
        "The meeting room is already booked", "The materials aren't ready yet",
        "The manager must travel for work that morning", "The manager wants more time to read"], 2),
    ("What will the employee take care of?", [
        "Rebooking the room and emailing the materials", "Going on the business trip instead",
        "Emailing everyone the new meeting time", "Printing the materials for 3 p.m."], 0),
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
    ("What does the neighbor ask for?", [
        "No music at all after ten", "Quieter music after ten at night",
        "Music only until she falls asleep", "An apology for last night"], 1),
    ("How does the man respond?", [
        "He says he'll just turn it down a little", "He says he didn't play any music",
        "He offers to stop playing after ten", "He'll listen through headphones from now on"], 3),
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
    ("What is the junior worried about?", [
        "Not knowing much about the company", "Explaining why she wants the job",
        "Her language skills not being good enough", "Having no experience abroad"], 1),
    ("What does the senior advise?", [
        "Say her real reason plainly and back it with an example", "Find a more impressive reason than languages",
        "Focus only on the company's overseas business", "Talk about several experiences"], 0),
    ("What will she add to her answer?", [
        "The company's overseas deals", "A story about a trip abroad",
        "Her time studying in another country", "A list of the languages she speaks"], 2),
])

dialogue("n3-hospital-visit", "Visiting a friend in hospital", 3, "health", ("なおみ", F), ("けんじ", M), [
    ("A", "けんじさん、具合はどう？", "Kenji, how are you feeling?", ["具合"]),
    ("B", "だいぶよくなったよ。来週には退院できるって。", "Much better. They say I can leave next week.", ["退院"]),
    ("A", "よかった。足の手術、大変だったね。", "That's great. The leg surgery must have been hard.", ["手術"]),
    ("B", "うん、でもリハビリのほうがつらいかな。", "Yeah, but the rehab is tougher.", ["リハビリ"]),
    ("A", "これ、お見舞い。好きな雑誌を持ってきたよ。", "This is for you. I brought your favorite magazine.", ["お見舞い", "雑誌"]),
    ("B", "ありがとう。ちょうど退屈してたんだ。", "Thanks. I was just getting bored.", ["退屈"]),
], [
    ("What does Kenji find hardest?", [
        "The operation on his leg", "Waiting until next week",
        "Being bored in hospital", "The recovery exercises"], 3),
    ("Why is Kenji especially glad of Naomi's gift?", [
        "He had nothing to do", "He'd asked her to bring it",
        "He'll be leaving next week", "It helps with his rehab"], 0),
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
    ("Why does the agent advise booking early?", [
        "Hotel plans sell out before flights", "The trip is four days long",
        "Lots of people travel then", "The package deal is ending soon"], 2),
    ("What kind of plan does the agent recommend?", [
        "A hotel-only plan for three nights", "A package with flights and a hotel",
        "A trip timed after the festival", "A four-night hotel package"], 1),
])

dialogue("n3-volunteer", "Volunteering", 3, "social", ("リサ", FY), ("たけし", MY), [
    ("A", "週末、公園のごみ拾いのボランティアに参加するんだけど、一緒にどう？", "I'm joining a park cleanup volunteer event this weekend. Want to come?", ["公園", "ボランティア", "参加"]),
    ("B", "いいね。何時から？", "Sounds good. What time?", []),
    ("A", "朝八時に公園の入り口に集合だよ。", "We meet at the park entrance at 8 a.m.", ["入り口", "集合"]),
    ("B", "早いなあ。何か持っていくものある？", "That's early. Should I bring anything?", []),
    ("A", "軍手と飲み物だけで大丈夫。袋は用意してくれるって。", "Just work gloves and a drink. They'll provide bags.", ["軍手", "用意"]),
    ("B", "わかった。じゃあ、行くよ。", "Got it. I'll come then.", []),
], [
    ("What does Takeshi need to bring?", [
        "Gloves, a drink and trash bags", "Only trash bags",
        "Gloves and something to drink", "Nothing; everything is provided"], 2),
    ("How does Takeshi react to the invitation?", [
        "He finds the start early but agrees to go", "He says it's too early and declines",
        "He agrees only if bags are provided", "He'll come later in the morning"], 0),
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
    ("What is true about the man's ID?", [
        "He must bring a passport instead", "His residence card is accepted",
        "He must write his ID number on the form", "He doesn't need any ID"], 1),
    ("What does the teller say about the fee?", [
        "It's 4,000 yen in total", "It's 400 yen per transfer",
        "It's waived with a residence card", "It's charged each time he sends money"], 3),
])

dialogue("n3-environment", "The plastic bag charge", 3, "society", ("佐藤", F), ("木村", M), [
    ("A", "来月から、会社の売店でレジ袋が有料になるって聞いた？", "Did you hear the company shop will start charging for plastic bags next month?", ["売店", "有料"]),
    ("B", "え、そうなの？五円でも、毎日だと面倒だなあ。", "Really? Even if it's five yen, doing that every day is a pain.", ["面倒"]),
    ("A", "でも、ごみが減るなら、いいことだと思うけど。", "But if it cuts down on trash, I think it's a good thing.", ["ごみ", "減る"]),
    ("B", "一人が袋を断っても、環境はそんなに変わらないんじゃない？", "Even if one person refuses a bag, the environment won't change that much, will it?", ["環境"]),
    ("A", "一人ならそうかもしれないけど、会社全体だと結構な量になるよ。", "Maybe not for one person, but across the whole company it adds up to quite a lot.", ["全体", "量"]),
    ("B", "うーん、確かに。昼ごはんを買う人、多いもんね。", "Hmm, true. Lots of people buy their lunch there.", ["確か"]),
    ("A", "それに、売店でエコバッグを無料で配るらしいよ。", "Also, apparently the shop will hand out eco bags for free.", ["無料", "配る"]),
    ("B", "それなら、僕も使ってみようかな。", "In that case, maybe I'll try using one too.", []),
    ("A", "じゃあ、今度の会議で、みんなにも知らせようか。", "Then shall we tell everyone at the next meeting?", ["会議"]),
    ("B", "いいね。お知らせのポスターは僕が作るよ。", "Good idea. I'll make a poster announcing it.", ["ポスター"]),
], [
    ("Why is Kimura against the charge at first?", [
        "He thinks five yen is too much for a bag", "He rarely buys lunch at the shop",
        "Paying for a bag every day sounds annoying", "He doesn't want to make a poster"], 2),
    ("What changes Kimura's mind?", [
        "Hearing that the bags will cost only five yen", "The total for the whole company and the free bags",
        "Being told everyone will discuss it at the meeting", "Learning that fewer people buy lunch there"], 1),
    ("What do they decide?", [
        "Kimura hands out eco bags at the shop", "Sato makes a poster and Kimura speaks at the meeting",
        "They ask the shop to keep bags free", "Kimura makes a poster and they tell everyone at the meeting"], 3),
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
    ("What happens with the booking time?", [
        "They keep six o'clock for four people", "It moves to half past seven because six is full",
        "It moves to six thirty on Sunday", "It's put on a waiting list for six"], 1),
    ("What will the staff member do about the egg allergy?", [
        "Pass it on to the chef", "Suggest a different restaurant",
        "Ask the customer to call back", "Seat that person separately"], 0),
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
    ("Why is the woman replacing her phone?", [
        "Its text is too small to read", "Its battery doesn't last long",
        "Its data can't be moved", "Its settings are too hard to change"], 1),
    ("How will she get large text on the new phone?", [
        "By choosing a bigger model", "By having the staff move her data",
        "By buying a special app", "By changing it in the settings"], 3),
    ("What happens next?", [
        "She waits while the shop moves her data", "She moves her data herself at home",
        "She comes back in two days", "She tries another model first"], 0),
])

dialogue("n3-study-abroad", "Study abroad plans", 3, "school", ("父", MO), ("息子", MY), [
    ("A", "留学のこと、ちゃんと考えたのか。", "Have you thought seriously about studying abroad?", ["留学"]),
    ("B", "うん。一年間、カナダの大学で勉強したいと思ってる。", "Yes. I want to study at a Canadian university for a year.", ["大学"]),
    ("A", "費用はどうするんだ。", "What about the cost?", ["費用"]),
    ("B", "奨学金に申し込むつもりだよ。足りない分はアルバイトでためる。", "I plan to apply for a scholarship, and save the rest from part-time work.", ["奨学金", "アルバイト"]),
    ("A", "そこまで考えているなら、応援するよ。", "If you've thought it through that far, I'll support you.", ["応援"]),
    ("B", "ありがとう、お父さん。", "Thanks, Dad.", []),
], [
    ("How does the son plan to pay?", [
        "His father will cover what's missing", "A scholarship, topped up with his own earnings",
        "A part-time job in Canada", "A scholarship that covers everything"], 1),
    ("Why does the father agree?", [
        "The son has clearly planned it out", "It is only for one year",
        "The son already has a scholarship", "Canada is not expensive"], 0),
])

dialogue("n3-lost-way", "Lost in the city", 3, "directions", ("観光客", F), ("警察官", M), [
    ("A", "すみません、美術館へ行きたいんですが、道に迷ってしまって。", "Excuse me, I want to go to the art museum but I've gotten lost.", ["美術館", "道"]),
    ("B", "美術館なら、この通りを二百メートルほど行った先の交差点を左です。", "For the museum, go about 200 meters down this street and turn left at the intersection.", ["通り", "交差点"]),
    ("A", "交差点を左ですね。そこから遠いですか。", "Left at the intersection. Is it far from there?", []),
    ("B", "いいえ、曲がるとすぐ右側に見えます。", "No, once you turn you'll see it right away on the right.", ["右側"]),
    ("A", "今日は何時まで開いているかご存じですか。", "Do you know until what time it's open today?", []),
    ("B", "たしか五時までだったと思います。", "I believe it's until five.", []),
], [
    ("How does the tourist reach the museum?", [
        "Turn right at the intersection; it's on the left", "Go 200 meters past the intersection, then left",
        "Go 200 meters, turn left; it's just there on the right", "Turn left now; it's 200 meters along on the right"], 2),
    ("How sure is the police officer about the closing time?", [
        "He isn't certain, but thinks it's five", "He knows it closes at five",
        "He doesn't know at all", "He says it depends on the day"], 0),
])

dialogue("n3-overtime", "Overtime", 3, "work", ("同僚A", F), ("同僚B", M), [
    ("A", "最近、毎日残業してるね。大丈夫？", "You've been working overtime every day lately. Are you okay?", ["残業"]),
    ("B", "新しいプロジェクトの締め切りが近くて。", "The new project's deadline is close.", ["プロジェクト", "締め切り"]),
    ("A", "一人で抱え込まないで、手伝えることがあったら言ってね。", "Don't take it all on yourself; tell me if I can help.", ["一人"]),
    ("B", "ありがとう。じゃあ、この資料の確認をお願いしてもいい？", "Thanks. Then could I ask you to check these documents?", ["資料", "確認"]),
    ("A", "もちろん。明日の朝までにやっておくよ。", "Of course. I'll do it by tomorrow morning.", []),
    ("B", "本当に助かる。", "That really helps.", ["助かる"]),
], [
    ("Why has the man been staying late?", [
        "He was asked to check documents", "He wants to finish by tomorrow morning",
        "He is doing the project alone", "A new project is almost due"], 3),
    ("What does the woman agree to do?", [
        "Stay late with him tonight", "Look over some documents by morning",
        "Take over part of the project", "Ask for the deadline to be moved"], 1),
])

dialogue("n3-earthquake", "Earthquake preparation", 3, "safety", ("母", F), ("娘", FY), [
    ("A", "防災グッズ、ちゃんと準備してある？", "Have you properly prepared your emergency kit?", ["防災", "準備"]),
    ("B", "水と懐中電灯はあるけど、ほかに何が必要かな。", "I have water and a flashlight, but what else do I need?", ["懐中電灯", "必要"]),
    ("A", "三日分の食料と、薬と、携帯の充電器も入れておきなさい。", "Put in three days of food, medicine, and a phone charger too.", ["食料", "充電器"]),
    ("B", "避難場所はどこだっけ。", "Where's the evacuation site again?", ["避難"]),
    ("A", "近くの小学校よ。家族で一度確認しておこうね。", "The nearby elementary school. Let's check it once as a family.", ["小学校"]),
    ("B", "わかった。今度の日曜日に行ってみよう。", "Okay. Let's go this Sunday.", []),
], [
    ("What does the daughter still need to add to her kit?", [
        "Water and a flashlight", "Food, medicine and a charger",
        "A flashlight and a charger", "Three days of water"], 1),
    ("What will the family do on Sunday?", [
        "Buy the missing items", "Practice leaving the house quickly",
        "Go and see the evacuation site", "Visit the daughter's old school"], 2),
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
    ("What went wrong with the order?", [
        "He received a blue shirt instead of a red one", "The shirt arrived a day late",
        "He was sent a red shirt instead of a blue one", "The order number was wrong"], 2),
    ("What happens to the red shirt?", [
        "He sends it back, and the shop pays the postage", "He keeps it as an apology",
        "He pays to send it back", "The shop collects it with the new one"], 0),
])

dialogue("n3-neighborhood-festival", "Neighborhood festival", 3, "social", ("町内会長", MO), ("新住民", F), [
    ("A", "来月、町内のお祭りがあるんですが、手伝っていただけませんか。", "There's a neighborhood festival next month. Could you help out?", ["町内", "祭り"]),
    ("B", "ぜひ。どんなことをすればいいですか。", "Gladly. What should I do?", []),
    ("A", "焼きそばの屋台をお願いしたいんです。", "We'd like you to help at the yakisoba stall.", ["焼きそば", "屋台"]),
    ("B", "料理はあまり得意じゃないんですが…。", "I'm not very good at cooking, though…", ["得意"]),
    ("A", "大丈夫ですよ。ほかの人が作るので、お金を受け取る係です。", "That's fine. Others will cook; you'd handle the money.", ["係"]),
    ("B", "それならできます。楽しみです。", "Then I can do it. I'm looking forward to it.", ["楽しみ"]),
], [
    ("Why does the woman hesitate at first?", [
        "She wasn't sure what the job involved", "She thought she'd have to cook",
        "She worried about handling money", "She thought the stall was her own"], 1),
    ("What will she do at the festival?", [
        "Cook yakisoba with the others", "Help the chairman organize the stalls",
        "Buy yakisoba for the neighbors", "Take payments at the yakisoba stall"], 3),
])


HERE = Path(__file__).resolve().parent
OUT = HERE / "dialogues.json"
BATCHES = HERE / "batches"
NOTE = "Dialogues drafted by an LLM; review with tools/items/review.py before marking verified."

DRAFT_SYSTEM = (
    "You write original two-speaker Japanese listening dialogues for learners. Never copy textbooks, apps, dramas "
    "or test items; invent names and places; keep it PG. Japanese must be natural, grammatical and at the requested "
    "JLPT level (NFC, full-width punctuation, no romaji or furigana). Reply with a single JSON object only."
)


def draft_messages(args, avoid: list[str]) -> list[dict]:
    natural = args.style == "natural"
    lo, hi = (10, 16) if natural else (7, 11)
    user = (
        f"Write one JLPT N{args.level} listening dialogue, style {args.style}"
        + (f", topic: {args.topic}" if args.topic else "")
        + f". {lo}-{hi} lines, exactly two speakers A and B.\n"
        + (
            "Natural style: unscripted-sounding speech. Wrap every filler, hesitation and abandoned restart in "
            "braces, e.g. {えっと、}{あの、}{なんか}{明日、あ、}明後日; at least 4 braced spans; braces never nest. "
            "Backchannels (うん。へえ。そうなんだ。) are their own short lines by the other speaker with "
            "\"overlap\": true, and so is a line that interrupts.\n"
            if natural else ""
        )
        + "Each line has gaps: 1-3 content words that appear verbatim in the line in dictionary form (not inside "
        "braces). Then 2-3 English comprehension questions with 4 choices each and a 0-based answer index. "
        "Questions test understanding (why, what changed, what they decide, what is implied), not keyword spotting; "
        "every distractor must be something mentioned or suggested in the dialogue that is not the answer.\n"
        + (f"Avoid these titles/situations: {'; '.join(avoid[-30:])}.\n" if avoid else "")
        + 'JSON: {"title": English, "topic": short English tag, "speakers": [{"id":"A","name":Japanese name or '
        'role,"voice":"female|male","age":"young|adult|senior","hint":English delivery note}, {...B}], '
        '"lines": [{"speaker":"A","ja":...,"en":...,"gaps":[...],"overlap":false}], '
        '"questions": [{"question":...,"choices":[4 strings],"answer":0}]}'
    )
    return [{"role": "system", "content": DRAFT_SYSTEM}, {"role": "user", "content": user}]


def normalize(args, raw: dict) -> dict:
    nfc = pa.build_practice.nfc
    return {
        "id": "", "title": str(raw["title"]).strip(), "jlpt": args.level, "topic": str(raw.get("topic", "")).strip(),
        "style": args.style,
        "speakers": [
            {k: (nfc(str(sp[k])) if k == "name" else str(sp[k])) for k in ("id", "name", "voice", "age")}
            | ({"hint": str(sp["hint"])} if sp.get("hint") else {})
            for sp in raw["speakers"]
        ],
        "lines": [
            {"speaker": ln["speaker"], "ja": nfc(ln["ja"]), "en": ln["en"], "gaps": [nfc(g) for g in ln.get("gaps", [])]}
            | ({"overlap": True} if ln.get("overlap") else {})
            for ln in raw["lines"]
        ],
        "questions": [
            {"question": q["question"], "choices": list(q["choices"]), "answer": int(q["answer"])} for q in raw["questions"]
        ],
    }


def merge(force: bool = False) -> None:
    authored = [("author_dialogues.py", d) for d in D] + pa.load_batches(BATCHES, "dialogues")
    counts = pa.merge(OUT, "dialogues", authored, NOTE, force)
    print(f"dialogues.json: {counts['total']} dialogues ({counts['authored']} authored, "
          f"{counts['keptReviewed']} reviewed copies kept, {counts['keptJsonOnly']} only in the JSON)")


def main() -> None:
    sys.stdout.reconfigure(encoding="utf-8")
    ap = argparse.ArgumentParser(description="Merge or draft listening dialogues.")
    ap.add_argument("--force", action="store_true", help="replace reviewed copies with the authored version")
    sub = ap.add_subparsers(dest="cmd")
    d = sub.add_parser("draft", help="draft new dialogues through an OpenAI-compatible endpoint")
    pa.add_draft_args(d)
    d.add_argument("--level", type=int, required=True, choices=(1, 2, 3, 4, 5), help="JLPT level (5 = N5)")
    d.add_argument("--style", choices=("scripted", "natural"), default="scripted")
    d.add_argument("--topic", help="optional situation to write about")
    args = ap.parse_args()
    if args.cmd == "draft":
        prefix = "nat-" if args.style == "natural" else ""
        code = pa.draft(
            args=args, key="dialogues", out=OUT, batch_dir=BATCHES,
            messages=lambda avoid: draft_messages(args, avoid),
            normalize=lambda raw: normalize(args, raw),
            check=pa.build_practice.check_dialogue,
            id_base=lambda e: f"{prefix}n{args.level}-{pa.slug(e['title'])}",
        )
        merge(args.force)
        sys.exit(code)
    merge(args.force)


if __name__ == "__main__":
    main()
