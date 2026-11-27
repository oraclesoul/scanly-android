import XCTest

final class ScanlyUITests: XCTestCase {
    private var app: XCUIApplication!

    override func setUpWithError() throws {
        continueAfterFailure = false
        app = XCUIApplication()
        app.launchArguments = ["-resetData", "-seedDemo", "-AppleLanguages", "(en)", "-AppleLocale", "en_US"]
        app.launch()
    }

    // MARK: - Helpers

    private func shot(_ name: String) {
        let screenshot = XCUIScreen.main.screenshot()
        let attachment = XCTAttachment(screenshot: screenshot)
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
        if let dir = ProcessInfo.processInfo.environment["SCREENSHOT_DIR"] {
            try? FileManager.default.createDirectory(atPath: dir, withIntermediateDirectories: true)
            try? screenshot.pngRepresentation.write(to: URL(fileURLWithPath: dir).appendingPathComponent("\(name).png"))
        }
    }

    @discardableResult
    private func wait(_ element: XCUIElement, _ timeout: TimeInterval = 30, file: StaticString = #filePath, line: UInt = #line) -> XCUIElement {
        XCTAssertTrue(element.waitForExistence(timeout: timeout), "Missing: \(element)", file: file, line: line)
        return element
    }

    private func replaceText(in field: XCUIElement, with text: String) {
        field.tap()
        if let current = field.value as? String, !current.isEmpty, current != "Name", current != "PDF name" {
            field.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: current.count))
        }
        field.typeText(text)
    }

    private func goBack() {
        app.navigationBars.buttons.element(boundBy: 0).tap()
    }

    // MARK: - Test

    func testFullFlow() throws {
        // Home starts on the PDFs tab, which is empty
        wait(app.staticTexts["No PDFs yet"])
        XCTAssertTrue(app.buttons["scanButton"].exists)
        XCTAssertTrue(app.buttons["importButton"].exists)
        shot("01-home-pdfs-empty")

        // Scans tab has the seeded document (waits for the demo pages to finish processing)
        app.buttons["Scans"].tap()
        let scanRow = wait(app.otherElements["scanRow.0"], 60)
        shot("02-home-scans")
        scanRow.tap()

        // Four pages, all Cropped
        wait(app.buttons["select.3"], 90)
        XCTAssertFalse(app.buttons["select.4"].exists)
        shot("03-document")

        // Select pages 2 and 3 and apply Xerox to just those
        app.buttons["select.1"].tap()
        app.buttons["select.2"].tap()
        wait(app.staticTexts["2 selected"])
        app.buttons["filter.xerox"].tap()
        let xerox = app.staticTexts.matching(NSPredicate(format: "label == 'Xerox'"))
        let predicate = NSPredicate(format: "count == 2")
        expectation(for: predicate, evaluatedWith: xerox)
        waitForExpectations(timeout: 60)
        shot("04-selected-xerox")

        // Delete the two selected pages
        app.buttons["deleteSelected"].tap()
        wait(app.alerts.firstMatch)
        app.alerts.buttons["Delete"].tap()
        wait(app.buttons["select.1"])
        XCTAssertFalse(app.buttons["select.2"].exists, "Two pages should have been deleted")
        shot("05-after-delete")

        // Create a PDF with a custom name
        app.buttons["createPDF"].tap()
        let nameField = wait(app.textFields["pdfNameField"])
        shot("06-export-sheet")
        replaceText(in: nameField, with: "Invoice Final")
        app.buttons["createConfirm"].tap()
        wait(app.staticTexts["Invoice Final.pdf"], 90)
        shot("07-pdf-saved")
        app.buttons["doneButton"].tap()

        // Back to Home: the PDF is listed
        goBack()
        wait(app.buttons["PDFs"]).tap()
        wait(app.staticTexts["Invoice Final"])
        shot("08-home-pdf-list")

        // View it
        app.staticTexts["Invoice Final"].tap()
        wait(app.otherElements["pdfViewer"])
        sleep(2)
        shot("09-pdf-viewer")
        goBack()

        // Rename it
        wait(app.buttons["pdfMenu.0"]).tap()
        wait(app.buttons["Rename"]).tap()
        let renameField = wait(app.alerts.textFields.firstMatch)
        replaceText(in: renameField, with: "Bank Statement")
        shot("10-rename")
        app.alerts.buttons["Save"].tap()
        wait(app.staticTexts["Bank Statement"])
        XCTAssertFalse(app.staticTexts["Invoice Final"].exists)

        // Delete it
        app.buttons["pdfMenu.0"].tap()
        wait(app.buttons["Delete"]).tap()
        wait(app.alerts.firstMatch)
        app.alerts.buttons["Delete"].tap()
        wait(app.staticTexts["No PDFs yet"])
        shot("11-pdf-deleted")
    }

    func testPageEditorAndSettings() throws {
        app.buttons["Scans"].tap()
        wait(app.otherElements["scanRow.0"], 60).tap()
        wait(app.buttons["select.3"], 90)

        // Open the first page and switch to Xerox, then rotate
        app.otherElements["page.0"].tap()
        wait(app.buttons["editFilter.xerox"]).tap()
        sleep(2)
        shot("20-page-editor")
        app.buttons["Rotate right"].tap()
        sleep(3)
        shot("21-rotated")

        // Crop screen
        app.buttons["Crop"].tap()
        wait(app.buttons["cropDone"])
        sleep(2)
        shot("22-crop")
        app.buttons["cropDone"].tap()
        wait(app.buttons["editFilter.xerox"])

        // Settings
        app.navigationBars.buttons.element(boundBy: 0).tap()
        goBack()
        wait(app.buttons["settingsButton"]).tap()
        wait(app.staticTexts["locationLabel"])
        shot("23-settings")
    }
}
