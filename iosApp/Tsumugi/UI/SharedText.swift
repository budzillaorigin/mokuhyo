import Foundation
import Shared

/// Labels from the shared string table (BRIEF_V2 G-14, DECISIONS D-109), in the app's language. Android maps through
/// the same table, so both apps name stages, kinds, Today blocks, phases and ratings identically.
enum SharedText {
    /// Tells the shared core which language the app runs in (the bundle's preferred localization).
    static func setLanguage() {
        L10n.shared.setLanguage(tag: Bundle.main.preferredLocalizations.first)
    }

    private static var locale: AppLocale { L10n.shared.locale }

    static func stage(_ stage: Stage) -> String { Labels.shared.stage(stage: stage, locale: locale) }
    static func kind(_ kind: ItemKind) -> String { Labels.shared.kind(kind: kind, locale: locale) }
    static func block(_ block: TodayBlockKind) -> String { Labels.shared.block(block: block, locale: locale) }
    static func phase(_ phase: LearningPhase) -> String { Labels.shared.phase(phase: phase, locale: locale) }
    static func rating(_ rating: Rating) -> String { Labels.shared.rating(rating: rating, locale: locale) }
}
